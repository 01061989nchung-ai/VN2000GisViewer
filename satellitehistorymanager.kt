package vn.gis.vn2000viewer.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import vn.gis.vn2000viewer.crs.VN2000ProjectionHelper
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sinh
import kotlin.math.tan

/**
 * Bộ quản lý tải, lưu đệm (RAM + Disk) và kéo tham chiếu (Reproject & Interactive Calibration)
 * ảnh Google Satellite / Google Earth lịch sử các năm từ hệ tọa độ Web Mercator (EPSG:3857 / WGS84)
 * về hệ tọa độ phẳng VN-2000.
 *
 * Tính năng cốt lõi:
 * 1. Tải mảnh ảnh (Tiles) từ Google Satellite, Google Hybrid và kho ảnh vệ tinh lịch sử các năm (2014 - 2026)
 *    qua giao thức Esri World Imagery Wayback WMTS (Nguồn: https://github.com/Esri/wayback-core).
 * 2. Tự động nắn chỉnh hình học (Mesh Warping 5x5 nút = 16 ô tứ giác) từ EPSG:3857 sang tọa độ phẳng
 *    VN-2000 (X Bắc, Y Đông) theo 7 tham số Helmert chuẩn (Quyết định 05/2007/QĐ-BTNMT - EPSG:4756 / EPSG:6960).
 * 3. Lưu trữ thông số "Kéo tham chiếu thủ công" (Manual Georeferencing Shift ΔX Bắc, ΔY Đông, Scale, Rotation)
 *    riêng cho từng năm để khắc phục sai số vệ tinh giữa các thời kỳ hoặc khớp với bản đồ DGN VN-2000 nội bộ.
 * 4. Hỗ trợ nạp ảnh chụp Google Earth lịch sử từ bộ nhớ máy (.jpg/.png) và neo vào khung tọa độ VN-2000 hiện tại
 *    để người dùng kéo tham chiếu trực tiếp bằng ngón tay trên màn hình.
 */
class SatelliteHistoryManager(private val context: Context) {

    data class SatelliteEpoch(
        val id: String,
        val label: String,
        val year: Int,
        val urlTemplate: String,
        val isGoogleSource: Boolean
    ) {
        override fun toString(): String = label
    }

    /**
     * Tham số hiệu chỉnh kéo tham chiếu (Georeferencing Calibration) từ Google Earth về VN-2000
     * cho từng mốc năm (cho phép khắc phục lệch ảnh giữa các năm hoặc lệch lưới VN-2000 nội bộ).
     */
    data class CalibrationTransform(
        var shiftEastMeters: Double = 0.0,
        var shiftNorthMeters: Double = 0.0,
        var scaleMultiplier: Double = 1.0,
        var rotationDeg: Double = 0.0
    )

    /**
     * Lớp ảnh Google Earth lịch sử do người dùng nhập từ tệp ảnh (.jpg/.png) và kéo tham chiếu trực tiếp.
     */
    data class CustomGoogleEarthPhoto(
        val fileName: String,
        val yearLabel: String,
        val bitmap: Bitmap,
        var boundsVn2000: GisFileLoader.WorldBounds,
        var rotationDeg: Float = 0f
    )

    data class TileKey(
        val epochId: String,
        val z: Int,
        val x: Int,
        val y: Int
    ) {
        fun cacheKey(): String = "${epochId}_${z}_${x}_${y}"
    }

    data class ReprojectedTile(
        val key: TileKey,
        val bitmap: Bitmap,
        /**
         * Mảng 25 điểm (5x5 lưới) lưu tọa độ VN-2000 (Y_Đông, X_Bắc) tương ứng với các nút lưới trên tile 256x256px.
         * Cấu trúc: [east0, north0, east1, north1, ...] (50 phần tử Double).
         */
        val vn2000MeshCoords: DoubleArray,
        val centralMeridian: Double,
        val zoneWidth: VN2000ProjectionHelper.ZoneWidth
    )

    companion object {
        const val MESH_SUBDIVISIONS = 4
        const val TILE_SIZE_PX = 256
        private const val MAX_RAM_CACHE_KB = 32 * 1024 // 32 MB RAM Cache

        val DEFAULT_EPOCHS: List<SatelliteEpoch> = listOf(
            SatelliteEpoch(
                id = "google_sat_now",
                label = "2026 - Google Satellite (Hiện tại)",
                year = 2026,
                urlTemplate = "https://mt1.google.com/vt/lyrs=s&x={x}&y={y}&z={z}",
                isGoogleSource = true
            ),
            SatelliteEpoch(
                id = "google_hyb_now",
                label = "2026 - Google Hybrid (Kèm tên đường)",
                year = 2026,
                urlTemplate = "https://mt1.google.com/vt/lyrs=y&x={x}&y={y}&z={z}",
                isGoogleSource = true
            ),
            SatelliteEpoch(
                id = "wb_2025",
                label = "2025 - Ảnh vệ tinh lịch sử (Wayback)",
                year = 2025,
                urlTemplate = "https://wayback.maptiles.arcgis.com/arcgis/rest/services/World_Imagery/WMTS/1.0.0/default028mm/MapServer/tile/47964/{z}/{y}/{x}",
                isGoogleSource = false
            ),
            SatelliteEpoch(
                id = "wb_2024",
                label = "2024 - Ảnh vệ tinh lịch sử (Wayback)",
                year = 2024,
                urlTemplate = "https://wayback.maptiles.arcgis.com/arcgis/rest/services/World_Imagery/WMTS/1.0.0/default028mm/MapServer/tile/31144/{z}/{y}/{x}",
                isGoogleSource = false
            ),
            SatelliteEpoch(
                id = "wb_2023",
                label = "2023 - Ảnh vệ tinh lịch sử (2023-12-07)",
                year = 2023,
                urlTemplate = "https://wayback.maptiles.arcgis.com/arcgis/rest/services/World_Imagery/WMTS/1.0.0/default028mm/MapServer/tile/56102/{z}/{y}/{x}",
                isGoogleSource = false
            ),
            SatelliteEpoch(
                id = "wb_2022",
                label = "2022 - Ảnh vệ tinh lịch sử (Wayback)",
                year = 2022,
                urlTemplate = "https://wayback.maptiles.arcgis.com/arcgis/rest/services/World_Imagery/WMTS/1.0.0/default028mm/MapServer/tile/45134/{z}/{y}/{x}",
                isGoogleSource = false
            ),
            SatelliteEpoch(
                id = "wb_2021",
                label = "2021 - Ảnh vệ tinh lịch sử (Wayback)",
                year = 2021,
                urlTemplate = "https://wayback.maptiles.arcgis.com/arcgis/rest/services/World_Imagery/WMTS/1.0.0/default028mm/MapServer/tile/27996/{z}/{y}/{x}",
                isGoogleSource = false
            ),
            SatelliteEpoch(
                id = "wb_2020",
                label = "2020 - Ảnh vệ tinh lịch sử (Wayback)",
                year = 2020,
                urlTemplate = "https://wayback.maptiles.arcgis.com/arcgis/rest/services/World_Imagery/WMTS/1.0.0/default028mm/MapServer/tile/23001/{z}/{y}/{x}",
                isGoogleSource = false
            ),
            SatelliteEpoch(
                id = "wb_2019",
                label = "2019 - Ảnh vệ tinh lịch sử (Wayback)",
                year = 2019,
                urlTemplate = "https://wayback.maptiles.arcgis.com/arcgis/rest/services/World_Imagery/WMTS/1.0.0/default028mm/MapServer/tile/13661/{z}/{y}/{x}",
                isGoogleSource = false
            ),
            SatelliteEpoch(
                id = "wb_2018",
                label = "2018 - Ảnh vệ tinh lịch sử (2018-01-31)",
                year = 2018,
                urlTemplate = "https://wayback.maptiles.arcgis.com/arcgis/rest/services/World_Imagery/WMTS/1.0.0/default028mm/MapServer/tile/10768/{z}/{y}/{x}",
                isGoogleSource = false
            ),
            SatelliteEpoch(
                id = "wb_2016",
                label = "2016 - Ảnh vệ tinh lịch sử (Wayback)",
                year = 2016,
                urlTemplate = "https://wayback.maptiles.arcgis.com/arcgis/rest/services/World_Imagery/WMTS/1.0.0/default028mm/MapServer/tile/7123/{z}/{y}/{x}",
                isGoogleSource = false
            ),
            SatelliteEpoch(
                id = "wb_2014",
                label = "2014 - Ảnh vệ tinh lịch sử (2014-02-20)",
                year = 2014,
                urlTemplate = "https://wayback.maptiles.arcgis.com/arcgis/rest/services/World_Imagery/WMTS/1.0.0/default028mm/MapServer/tile/10/{z}/{y}/{x}",
                isGoogleSource = false
            )
        )
    }

    private val diskCacheDir = File(context.cacheDir, "sat_tiles").apply { mkdirs() }
    private val epochCalibrations = HashMap<String, CalibrationTransform>()

    private val memoryCache = object : LruCache<String, Bitmap>(MAX_RAM_CACHE_KB) {
        override fun sizeOf(key: String, value: Bitmap): Int {
            return (value.byteCount / 1024).coerceAtLeast(1)
        }
    }

    @Synchronized
    fun getCalibrationForEpoch(epochId: String): CalibrationTransform {
        return epochCalibrations.getOrPut(epochId) { CalibrationTransform() }
    }

    /**
     * Nạp ảnh chụp Google Earth lịch sử (tệp .jpg/.png lưu từ Google Earth Pro) và đặt khớp vào
     * khung nhìn VN-2000 hiện tại để người dùng kéo tham chiếu (kéo, phóng to/thu nhỏ, xoay) trực tiếp.
     */
    suspend fun loadCustomGoogleEarthImage(
        uri: Uri,
        displayName: String,
        yearLabel: String,
        targetViewportBounds: GisFileLoader.WorldBounds
    ): CustomGoogleEarthPhoto = withContext(Dispatchers.IO) {
        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, boundsOpts)
        }
        val rawW = boundsOpts.outWidth.coerceAtLeast(512)
        val rawH = boundsOpts.outHeight.coerceAtLeast(512)

        var sampleSize = 1
        while ((rawW / sampleSize) > 2048 || (rawH / sampleSize) > 2048) {
            sampleSize *= 2
        }

        val decodeOpts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        val bmp = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, decodeOpts)
        } ?: throw IllegalStateException("Không thể giải mã tệp ảnh Google Earth: $displayName")

        // Đặt khung ban đầu của ảnh chiếm 75% khung nhìn VN-2000 hiện tại và giữ đúng tỷ lệ khung hình
        val aspect = bmp.width.toDouble() / bmp.height.toDouble().coerceAtLeast(1.0)
        val targetH = targetViewportBounds.heightNorth * 0.75
        val targetW = targetH * aspect
        val cx = targetViewportBounds.centerEast
        val cy = targetViewportBounds.centerNorth

        val initialBounds = GisFileLoader.WorldBounds(
            minEast = cx - targetW * 0.5,
            minNorth = cy - targetH * 0.5,
            maxEast = cx + targetW * 0.5,
            maxNorth = cy + targetH * 0.5
        )

        CustomGoogleEarthPhoto(
            fileName = displayName,
            yearLabel = yearLabel,
            bitmap = bmp,
            boundsVn2000 = initialBounds,
            rotationDeg = 0f
        )
    }

    /**
     * Tải danh sách đầy đủ các mốc lịch sử từ cấu hình Wayback chính thức của Esri (nếu có mạng).
     */
    suspend fun fetchAvailableWaybackEpochs(): List<SatelliteEpoch> = withContext(Dispatchers.IO) {
        val base = ArrayList<SatelliteEpoch>()
        base.add(DEFAULT_EPOCHS[0])
        base.add(DEFAULT_EPOCHS[1])

        try {
            val url = URL("https://s3-us-west-2.amazonaws.com/config.maptiles.arcgis.com/waybackconfig.json")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 5000
                requestMethod = "GET"
            }
            if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                val jsonText = conn.inputStream.bufferedReader().use { it.readText() }
                val root = JSONObject(jsonText)
                val byYear = linkedMapOf<Int, SatelliteEpoch>()
                val keys = root.keys()
                while (keys.hasNext()) {
                    val releaseNum = keys.next()
                    val item = root.optJSONObject(releaseNum) ?: continue
                    val title = item.optString("itemTitle", "")
                    val itemUrl = item.optString("itemURL", "")
                    if (title.isBlank() || itemUrl.isBlank()) continue

                    val dateMatch = Regex("""(\d{4})-(\d{2})-(\d{2})""").find(title)
                    val year = dateMatch?.groupValues?.getOrNull(1)?.toIntOrNull() ?: continue
                    val dateStr = dateMatch.value
                    val normalizedTemplate = itemUrl
                        .replace("{level}", "{z}")
                        .replace("{row}", "{y}")
                        .replace("{col}", "{x}")

                    if (!byYear.containsKey(year)) {
                        byYear[year] = SatelliteEpoch(
                            id = "wb_$releaseNum",
                            label = "$year - Ảnh vệ tinh ($dateStr)",
                            year = year,
                            urlTemplate = normalizedTemplate,
                            isGoogleSource = false
                        )
                    }
                }
                if (byYear.isNotEmpty()) {
                    base.addAll(byYear.values.sortedByDescending { it.year })
                    return@withContext base
                }
            }
        } catch (_: Throwable) {
        }

        DEFAULT_EPOCHS
    }

    /**
     * Tính toán mức thu phóng (Zoom Z) và danh sách các mảnh Tile (x, y) trong hệ Web Mercator (EPSG:3857)
     * phủ kín khung nhìn VN-2000 hiện tại (đã tính bù độ lệch kéo tham chiếu ΔX, ΔY).
     */
    fun computeVisibleTiles(
        epoch: SatelliteEpoch,
        visibleVn2000Bounds: GisFileLoader.WorldBounds,
        pixelsPerMeter: Double,
        centralMeridian: Double,
        zoneWidth: VN2000ProjectionHelper.ZoneWidth
    ): List<TileKey> {
        val cal = getCalibrationForEpoch(epoch.id)
        val unshiftedBounds = GisFileLoader.WorldBounds(
            minEast = visibleVn2000Bounds.minEast - cal.shiftEastMeters,
            minNorth = visibleVn2000Bounds.minNorth - cal.shiftNorthMeters,
            maxEast = visibleVn2000Bounds.maxEast - cal.shiftEastMeters,
            maxNorth = visibleVn2000Bounds.maxNorth - cal.shiftNorthMeters
        )

        val sw = VN2000ProjectionHelper.vn2000ToWgs84(
            unshiftedBounds.minNorth,
            unshiftedBounds.minEast,
            centralMeridian,
            zoneWidth
        )
        val ne = VN2000ProjectionHelper.vn2000ToWgs84(
            unshiftedBounds.maxNorth,
            unshiftedBounds.maxEast,
            centralMeridian,
            zoneWidth
        )

        val minLat = min(sw.latitude, ne.latitude).coerceIn(-85.0, 85.0)
        val maxLat = max(sw.latitude, ne.latitude).coerceIn(-85.0, 85.0)
        val minLon = min(sw.longitude, ne.longitude).coerceIn(-180.0, 180.0)
        val maxLon = max(sw.longitude, ne.longitude).coerceIn(-180.0, 180.0)

        val metersPerPixel = (1.0 / pixelsPerMeter).coerceAtLeast(0.05)
        val midLatRad = Math.toRadians((minLat + maxLat) * 0.5)
        val earthCircumference = 2.0 * PI * 6378137.0
        val rawZoom = ln((earthCircumference * cos(midLatRad)) / (metersPerPixel * TILE_SIZE_PX)) / ln(2.0)
        val zoom = rawZoom.toInt().coerceIn(5, 19)

        val (minTileX, minTileY) = latLonToTileXY(maxLat, minLon, zoom)
        val (maxTileX, maxTileY) = latLonToTileXY(minLat, maxLon, zoom)

        val n = 1 shl zoom
        val clampedMinX = minTileX.coerceIn(0, n - 1)
        val clampedMaxX = maxTileX.coerceIn(0, n - 1)
        val clampedMinY = minTileY.coerceIn(0, n - 1)
        val clampedMaxY = maxTileY.coerceIn(0, n - 1)

        val tiles = ArrayList<TileKey>(16)
        for (tx in clampedMinX..clampedMaxX) {
            for (ty in clampedMinY..clampedMaxY) {
                tiles.add(TileKey(epoch.id, zoom, tx, ty))
                if (tiles.size >= 24) return tiles
            }
        }
        return tiles
    }

    suspend fun loadAndReprojectTile(
        epoch: SatelliteEpoch,
        key: TileKey,
        centralMeridian: Double,
        zoneWidth: VN2000ProjectionHelper.ZoneWidth
    ): ReprojectedTile? = withContext(Dispatchers.IO) {
        val bitmap = getOrFetchTileBitmap(epoch, key) ?: return@withContext null
        val meshCoords = buildVn2000MeshForTile(key, centralMeridian, zoneWidth)
        ReprojectedTile(
            key = key,
            bitmap = bitmap,
            vn2000MeshCoords = meshCoords,
            centralMeridian = centralMeridian,
            zoneWidth = zoneWidth
        )
    }

    fun buildVn2000MeshForTile(
        key: TileKey,
        centralMeridian: Double,
        zoneWidth: VN2000ProjectionHelper.ZoneWidth
    ): DoubleArray {
        val gridDim = MESH_SUBDIVISIONS + 1
        val coords = DoubleArray(gridDim * gridDim * 2)
        val n = (1 shl key.z).toDouble()

        var idx = 0
        for (row in 0..MESH_SUBDIVISIONS) {
            val fracY = row.toDouble() / MESH_SUBDIVISIONS.toDouble()
            val tileY = key.y + fracY
            val latRad = atan(sinh(PI * (1.0 - 2.0 * tileY / n)))
            val latDeg = Math.toDegrees(latRad)

            for (col in 0..MESH_SUBDIVISIONS) {
                val fracX = col.toDouble() / MESH_SUBDIVISIONS.toDouble()
                val tileX = key.x + fracX
                val lonDeg = (tileX / n) * 360.0 - 180.0

                val vn = VN2000ProjectionHelper.wgs84ToVn2000(
                    latitude = latDeg,
                    longitude = lonDeg,
                    centralMeridian = centralMeridian,
                    zoneWidth = zoneWidth
                )
                coords[idx++] = vn.yEast
                coords[idx++] = vn.xNorth
            }
        }
        return coords
    }

    private fun getOrFetchTileBitmap(epoch: SatelliteEpoch, key: TileKey): Bitmap? {
        val cKey = key.cacheKey()
        memoryCache.get(cKey)?.let { if (!it.isRecycled) return it }

        val diskFile = File(diskCacheDir, "$cKey.jpg")
        if (diskFile.exists() && diskFile.length() > 0L) {
            val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
            val bmp = BitmapFactory.decodeFile(diskFile.absolutePath, opts)
            if (bmp != null) {
                memoryCache.put(cKey, bmp)
                return bmp
            }
        }

        val tileUrl = epoch.urlTemplate
            .replace("{z}", key.z.toString())
            .replace("{x}", key.x.toString())
            .replace("{y}", key.y.toString())

        return try {
            val conn = (URL(tileUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 6000
                readTimeout = 6000
                setRequestProperty("User-Agent", "VN2000GisViewer/1.0 (Android GIS)")
            }
            if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                val bytes = conn.inputStream.use { it.readBytes() }
                if (bytes.isNotEmpty()) {
                    FileOutputStream(diskFile).use { it.write(bytes) }
                    val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
                    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                    if (bmp != null) {
                        memoryCache.put(cKey, bmp)
                        return bmp
                    }
                }
            }
            null
        } catch (_: Throwable) {
            null
        }
    }

    private fun latLonToTileXY(latDeg: Double, lonDeg: Double, zoom: Int): Pair<Int, Int> {
        val n = 2.0.pow(zoom.toDouble())
        val latRad = Math.toRadians(latDeg.coerceIn(-85.0511, 85.0511))
        val x = floor(((lonDeg + 180.0) / 360.0) * n).toInt()
        val y = floor((1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / PI) / 2.0 * n).toInt()
        return Pair(x, y)
    }

    fun clearMemoryCache() {
        memoryCache.evictAll()
    }
}