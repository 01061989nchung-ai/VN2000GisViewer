package vn.gis.vn2000viewer.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import vn.gis.vn2000viewer.crs.VN2000ProjectionHelper
import vn.gis.vn2000viewer.data.GisFileLoader
import vn.gis.vn2000viewer.data.SatelliteHistoryManager
import java.util.Locale
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Custom View chuyên dụng hiển thị và chồng lớp (overlay) bản đồ Vector DGN V8, Raster GeoTIFF,
 * Ảnh Vệ tinh Google Earth / Lịch sử các năm (2014 - 2026) và Vị trí GPS hiện tại (Current Location)
 * theo hệ tọa độ phẳng Quốc gia VN-2000.
 */
class MapCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    interface OnMapInteractionListener {
        fun onCoordinateTapped(
            vn2000Coord: VN2000ProjectionHelper.VN2000Coordinate,
            wgs84Coord: VN2000ProjectionHelper.Wgs84Coordinate,
            nearestEntityInfo: String?
        )

        fun onViewportChanged(
            scaleMetersPerPixel: Double,
            visibleEntityCount: Int,
            shiftNorthMeters: Double,
            shiftEastMeters: Double
        )
    }

    var interactionListener: OnMapInteractionListener? = null
    var gisFileLoader: GisFileLoader? = null
    var satelliteHistoryManager: SatelliteHistoryManager? = null

    var currentProvince: VN2000ProjectionHelper.ProvinceMeridian =
        VN2000ProjectionHelper.findProvinceByName("Lâm Đồng")
        private set

    var currentZoneWidth: VN2000ProjectionHelper.ZoneWidth =
        VN2000ProjectionHelper.ZoneWidth.ZONE_3_DEG
        private set

    var currentSatelliteEpoch: SatelliteHistoryManager.SatelliteEpoch =
        SatelliteHistoryManager.DEFAULT_EPOCHS.first()
        private set

    var satelliteAlpha: Int = 220
        set(value) {
            field = value.coerceIn(0, 255)
            invalidate()
        }

    var isCalibrationDragMode: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    private var dgnLayerData: GisFileLoader.DgnLayerData? = null
    private var geoTiffDataset: GisFileLoader.GeoTiffDataset? = null
    private var activeTileBitmap: Bitmap? = null
    private var activeTileBounds: GisFileLoader.WorldBounds? = null

    private var activeSatelliteTiles: List<SatelliteHistoryManager.ReprojectedTile> = emptyList()
    private var customGooglePhotos: MutableList<SatelliteHistoryManager.CustomGoogleEarthPhoto> = ArrayList()

    // Dữ liệu Vị trí hiện tại (GPS thực tế đã quy đổi sang VN-2000)
    private var gpsLatitudeWgs84: Double? = null
    private var gpsLongitudeWgs84: Double? = null
    private var gpsEastVn2000: Double? = null
    private var gpsNorthVn2000: Double? = null
    private var gpsAccuracyMeters: Float = 0f

    var isSatelliteLayerVisible: Boolean = true
        set(value) {
            field = value
            if (value) scheduleSatelliteTilesUpdate()
            invalidate()
        }

    var isDgnLayerVisible: Boolean = true
        set(value) {
            field = value
            invalidate()
        }

    var isGeoTiffLayerVisible: Boolean = true
        set(value) {
            field = value
            invalidate()
        }

    var isGridVisible: Boolean = true
        set(value) {
            field = value
            invalidate()
        }

    private var centerEast: Double = 585000.0
    private var centerNorth: Double = 1378000.0
    private var pixelsPerMeter: Double = 1.2

    private var tappedEast: Double? = null
    private var tappedNorth: Double? = null

    private val rasterPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val satellitePaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val customPhotoBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(250, 204, 21)
        style = Paint.Style.STROKE
        strokeWidth = 3f
        pathEffect = DashPathEffect(floatArrayOf(12f, 8f), 0f)
    }
    private val vectorStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val polygonFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 26f
        setShadowLayer(3f, 1f, 1f, Color.BLACK)
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(65, 148, 163, 184)
        strokeWidth = 1f
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(8f, 8f), 0f)
    }
    private val gridLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(180, 203, 213, 225)
        textSize = 22f
    }
    private val crosshairPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(251, 191, 36)
        strokeWidth = 3.5f
        style = Paint.Style.STROKE
    }

    // Bộ cọ vẽ chấm xanh Vị trí hiện tại (GPS Blue Dot & Vòng bán kính sai số)
    private val gpsAccuracyFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(45, 56, 189, 248)
        style = Paint.Style.FILL
    }
    private val gpsAccuracyStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(160, 56, 189, 248)
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val gpsHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
        setShadowLayer(6f, 0f, 2f, Color.BLACK)
    }
    private val gpsCenterDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(14, 165, 233)
        style = Paint.Style.FILL
    }

    private val reusablePath = Path()
    private val reusableDestRect = RectF()
    private val reusableMeshScreenVerts = FloatArray(
        (SatelliteHistoryManager.MESH_SUBDIVISIONS + 1) *
            (SatelliteHistoryManager.MESH_SUBDIVISIONS + 1) * 2
    )

    private val viewScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var tileDecodeJob: Job? = null
    private var satelliteFetchJob: Job? = null

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val scaleFactor = detector.scaleFactor.toDouble().coerceIn(0.5, 2.0)

                if (isCalibrationDragMode) {
                    val lastCustom = customGooglePhotos.lastOrNull()
                    if (lastCustom != null) {
                        val b = lastCustom.boundsVn2000
                        val cx = b.centerEast
                        val cy = b.centerNorth
                        val halfW = (b.widthEast * 0.5) * scaleFactor
                        val halfH = (b.heightNorth * 0.5) * scaleFactor
                        lastCustom.boundsVn2000 = GisFileLoader.WorldBounds(
                            minEast = cx - halfW,
                            minNorth = cy - halfH,
                            maxEast = cx + halfW,
                            maxNorth = cy + halfH
                        )
                    } else {
                        val cal = satelliteHistoryManager?.getCalibrationForEpoch(currentSatelliteEpoch.id)
                        if (cal != null) {
                            cal.scaleMultiplier = (cal.scaleMultiplier * scaleFactor).coerceIn(0.5, 2.0)
                        }
                    }
                    invalidate()
                    return true
                }

                val focusX = detector.focusX
                val focusY = detector.focusY
                val worldFocusEast = screenXToWorldEast(focusX)
                val worldFocusNorth = screenYToWorldNorth(focusY)

                pixelsPerMeter = (pixelsPerMeter * scaleFactor).coerceIn(0.002, 500.0)

                val newFocusEast = screenXToWorldEast(focusX)
                val newFocusNorth = screenYToWorldNorth(focusY)
                centerEast += (worldFocusEast - newFocusEast)
                centerNorth += (worldFocusNorth - newFocusNorth)

                invalidate()
                scheduleViewportUpdates()
                return true
            }
        }
    )

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float
            ): Boolean {
                val deltaEastMeters = distanceX / pixelsPerMeter
                val deltaNorthMeters = -distanceY / pixelsPerMeter

                if (isCalibrationDragMode) {
                    val moveEast = -deltaEastMeters
                    val moveNorth = -deltaNorthMeters

                    val lastCustom = customGooglePhotos.lastOrNull()
                    if (lastCustom != null) {
                        val b = lastCustom.boundsVn2000
                        lastCustom.boundsVn2000 = GisFileLoader.WorldBounds(
                            minEast = b.minEast + moveEast,
                            minNorth = b.minNorth + moveNorth,
                            maxEast = b.maxEast + moveEast,
                            maxNorth = b.maxNorth + moveNorth
                        )
                    } else {
                        val cal = satelliteHistoryManager?.getCalibrationForEpoch(currentSatelliteEpoch.id)
                        if (cal != null) {
                            cal.shiftEastMeters += moveEast
                            cal.shiftNorthMeters += moveNorth
                        }
                    }
                    invalidate()
                    return true
                }

                centerEast += deltaEastMeters
                centerNorth += deltaNorthMeters
                invalidate()
                scheduleViewportUpdates()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                val focusEast = screenXToWorldEast(e.x)
                val focusNorth = screenYToWorldNorth(e.y)
                pixelsPerMeter = (pixelsPerMeter * 1.8).coerceIn(0.002, 500.0)
                centerEast = focusEast
                centerNorth = focusNorth
                invalidate()
                scheduleViewportUpdates()
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                val touchEast = screenXToWorldEast(e.x)
                val touchNorth = screenYToWorldNorth(e.y)
                tappedEast = touchEast
                tappedNorth = touchNorth

                val vnCoord = VN2000ProjectionHelper.VN2000Coordinate(
                    xNorth = touchNorth,
                    yEast = touchEast,
                    centralMeridian = currentProvince.centralMeridianDeg,
                    zoneWidth = currentZoneWidth
                )
                val wgsCoord = VN2000ProjectionHelper.vn2000ToWgs84(
                    xNorth = touchNorth,
                    yEast = touchEast,
                    centralMeridian = currentProvince.centralMeridianDeg,
                    zoneWidth = currentZoneWidth
                )
                val nearestInfo = findNearestVectorEntityDescription(touchEast, touchNorth)
                interactionListener?.onCoordinateTapped(vnCoord, wgsCoord, nearestInfo)
                invalidate()
                return true
            }
        }
    )

    override fun onTouchEvent(event: MotionEvent): Boolean {
        var handled = scaleDetector.onTouchEvent(event)
        handled = gestureDetector.onTouchEvent(event) || handled
        if (event.actionMasked == MotionEvent.ACTION_UP && isCalibrationDragMode) {
            scheduleSatelliteTilesUpdate()
        }
        return handled || super.onTouchEvent(event)
    }

    fun setProjectionConfig(
        province: VN2000ProjectionHelper.ProvinceMeridian,
        zoneWidth: VN2000ProjectionHelper.ZoneWidth
    ) {
        val oldMeridian = currentProvince.centralMeridianDeg
        val oldZone = currentZoneWidth
        currentProvince = province
        currentZoneWidth = zoneWidth

        val convertedCenter = VN2000ProjectionHelper.transformBetweenVn2000Zones(
            xNorth = centerNorth,
            yEast = centerEast,
            srcMeridian = oldMeridian,
            srcZoneWidth = oldZone,
            dstMeridian = province.centralMeridianDeg,
            dstZoneWidth = zoneWidth
        )
        centerEast = convertedCenter.yEast
        centerNorth = convertedCenter.xNorth

        // Cập nhật lại tọa độ VN-2000 của chấm GPS nếu đang có vị trí
        val lat = gpsLatitudeWgs84
        val lon = gpsLongitudeWgs84
        if (lat != null && lon != null) {
            val vn = VN2000ProjectionHelper.wgs84ToVn2000(
                latitude = lat,
                longitude = lon,
                centralMeridian = province.centralMeridianDeg,
                zoneWidth = zoneWidth
            )
            gpsEastVn2000 = vn.yEast
            gpsNorthVn2000 = vn.xNorth
        }

        activeSatelliteTiles = emptyList()
        invalidate()
        scheduleSatelliteTilesUpdate()
    }

    /**
     * Cập nhật vị trí GPS hiện tại từ WGS84 (Lat, Lon), tự động quy đổi sang VN-2000 (X Bắc, Y Đông)
     * và di chuyển tâm bản đồ tới vị trí người dùng nếu `moveCamera = true`.
     */
    fun updateCurrentGpsLocation(
        latitudeWgs84: Double,
        longitudeWgs84: Double,
        accuracyMeters: Float,
        moveCamera: Boolean
    ): VN2000ProjectionHelper.VN2000Coordinate {
        gpsLatitudeWgs84 = latitudeWgs84
        gpsLongitudeWgs84 = longitudeWgs84
        gpsAccuracyMeters = accuracyMeters.coerceAtLeast(1f)

        val vnCoord = VN2000ProjectionHelper.wgs84ToVn2000(
            latitude = latitudeWgs84,
            longitude = longitudeWgs84,
            centralMeridian = currentProvince.centralMeridianDeg,
            zoneWidth = currentZoneWidth
        )
        gpsEastVn2000 = vnCoord.yEast
        gpsNorthVn2000 = vnCoord.xNorth

        if (moveCamera) {
            centerEast = vnCoord.yEast
            centerNorth = vnCoord.xNorth
            // Nếu đang zoom quá nhỏ thì tự động phóng tới tỷ lệ 1m = 2.5px để nhìn rõ thửa đất quanh vị trí đứng
            if (pixelsPerMeter < 1.2) {
                pixelsPerMeter = 2.5
            }
            scheduleViewportUpdates()
        }
        invalidate()
        return vnCoord
    }

    fun findEntityAtCoordinate(east: Double, north: Double): String? {
        return findNearestVectorEntityDescription(east, north)
    }

    fun setSatelliteEpoch(epoch: SatelliteHistoryManager.SatelliteEpoch) {
        if (currentSatelliteEpoch.id == epoch.id) return
        currentSatelliteEpoch = epoch
        activeSatelliteTiles = emptyList()
        invalidate()
        scheduleSatelliteTilesUpdate()
    }

    fun addCustomGoogleEarthPhoto(photo: SatelliteHistoryManager.CustomGoogleEarthPhoto) {
        customGooglePhotos.add(photo)
        invalidate()
    }

    fun resetCurrentCalibration() {
        satelliteHistoryManager?.getCalibrationForEpoch(currentSatelliteEpoch.id)?.apply {
            shiftEastMeters = 0.0
            shiftNorthMeters = 0.0
            scaleMultiplier = 1.0
            rotationDeg = 0.0
        }
        customGooglePhotos.clear()
        invalidate()
        scheduleSatelliteTilesUpdate()
    }

    fun setDgnData(data: GisFileLoader.DgnLayerData) {
        this.dgnLayerData = data
        fitToActiveLayers()
    }

    fun setGeoTiffData(dataset: GisFileLoader.GeoTiffDataset) {
        this.geoTiffDataset = dataset
        this.activeTileBitmap = null
        this.activeTileBounds = null
        fitToActiveLayers()
    }

    fun fitToActiveLayers() {
        val dgnBounds = if (isDgnLayerVisible) dgnLayerData?.bounds else null
        val tiffBounds = if (isGeoTiffLayerVisible) geoTiffDataset?.bounds else null

        val combined = when {
            dgnBounds != null && tiffBounds != null -> dgnBounds.union(tiffBounds)
            dgnBounds != null -> dgnBounds
            tiffBounds != null -> tiffBounds
            else -> dgnLayerData?.bounds ?: geoTiffDataset?.bounds ?: return
        }

        centerEast = combined.centerEast
        centerNorth = combined.centerNorth

        val viewW = width.coerceAtLeast(800).toDouble()
        val viewH = height.coerceAtLeast(1200).toDouble()
        val spanEast = combined.widthEast.coerceAtLeast(10.0)
        val spanNorth = combined.heightNorth.coerceAtLeast(10.0)

        val scaleX = (viewW * 0.84) / spanEast
        val scaleY = (viewH * 0.84) / spanNorth
        pixelsPerMeter = min(scaleX, scaleY).coerceIn(0.002, 500.0)

        invalidate()
        scheduleViewportUpdates()
    }

    fun getCurrentVisibleWorldBounds(): GisFileLoader.WorldBounds {
        val halfW = (width.coerceAtLeast(1) * 0.5) / pixelsPerMeter
        val halfH = (height.coerceAtLeast(1) * 0.5) / pixelsPerMeter
        return GisFileLoader.WorldBounds(
            minEast = centerEast - halfW,
            minNorth = centerNorth - halfH,
            maxEast = centerEast + halfW,
            maxNorth = centerNorth + halfH
        )
    }

    private fun scheduleViewportUpdates() {
        scheduleGeoTiffTileUpdate()
        scheduleSatelliteTilesUpdate()
    }

    private fun scheduleGeoTiffTileUpdate() {
        val dataset = geoTiffDataset ?: return
        val loader = gisFileLoader ?: return
        if (!isGeoTiffLayerVisible || width <= 0 || height <= 0) return

        tileDecodeJob?.cancel()
        tileDecodeJob = viewScope.launch {
            delay(180L)
            val visibleBounds = getCurrentVisibleWorldBounds()
            val decoded = loader.decodeViewportRasterTile(
                dataset = dataset,
                visibleWorldBounds = visibleBounds,
                screenWidthPx = width,
                screenHeightPx = height
            )
            if (decoded != null) {
                activeTileBitmap = decoded.first
                activeTileBounds = decoded.second
                invalidate()
            }
        }
    }

    private fun scheduleSatelliteTilesUpdate() {
        val manager = satelliteHistoryManager ?: return
        if (!isSatelliteLayerVisible || width <= 0 || height <= 0) return

        satelliteFetchJob?.cancel()
        satelliteFetchJob = viewScope.launch {
            delay(160L)
            val visibleBounds = getCurrentVisibleWorldBounds()
            val epoch = currentSatelliteEpoch
            val meridian = currentProvince.centralMeridianDeg
            val zone = currentZoneWidth

            val keys = manager.computeVisibleTiles(
                epoch = epoch,
                visibleVn2000Bounds = visibleBounds,
                pixelsPerMeter = pixelsPerMeter,
                centralMeridian = meridian,
                zoneWidth = zone
            )

            val loadedTiles = keys.map { key ->
                async(Dispatchers.IO) {
                    manager.loadAndReprojectTile(epoch, key, meridian, zone)
                }
            }.awaitAll().filterNotNull()

            if (loadedTiles.isNotEmpty()) {
                activeSatelliteTiles = loadedTiles
                invalidate()
            }
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (oldw == 0 && oldh == 0 && (dgnLayerData != null || geoTiffDataset != null)) {
            fitToActiveLayers()
        } else {
            scheduleViewportUpdates()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.rgb(15, 23, 42))

        val visibleBounds = getCurrentVisibleWorldBounds()

        // 1. Vẽ lớp nền ảnh Google Earth / Vệ tinh các năm đã kéo tham chiếu về VN-2000
        if (isSatelliteLayerVisible) {
            drawReprojectedSatelliteLayer(canvas, visibleBounds)
        }

        // 2. Vẽ lớp ảnh GeoTIFF cục bộ chồng lên
        if (isGeoTiffLayerVisible) {
            drawGeoTiffLayer(canvas, visibleBounds)
        }

        // 3. Vẽ lưới tọa độ phẳng VN-2000 (X Bắc, Y Đông)
        if (isGridVisible) {
            drawVn2000CoordinateGrid(canvas, visibleBounds)
        }

        // 4. Vẽ chồng lớp dữ liệu Vector DGN V8 với Viewport Clipping & LOD
        var renderedEntities = 0
        if (isDgnLayerVisible) {
            renderedEntities = drawDgnVectorLayer(canvas, visibleBounds)
        }

        // 5. Vẽ chấm xanh Vị trí GPS hiện tại (Current Location) trên hệ tọa độ VN-2000
        drawCurrentGpsLocationMarker(canvas)

        // 6. Vẽ điểm đánh dấu vị trí chạm (Crosshair)
        drawTappedCrosshair(canvas)

        val cal = satelliteHistoryManager?.getCalibrationForEpoch(currentSatelliteEpoch.id)
        val metersPerPixel = 1.0 / pixelsPerMeter
        interactionListener?.onViewportChanged(
            scaleMetersPerPixel = metersPerPixel,
            visibleEntityCount = renderedEntities,
            shiftNorthMeters = cal?.shiftNorthMeters ?: 0.0,
            shiftEastMeters = cal?.shiftEastMeters ?: 0.0
        )
    }

    private fun drawReprojectedSatelliteLayer(
        canvas: Canvas,
        visibleBounds: GisFileLoader.WorldBounds
    ) {
        satellitePaint.alpha = satelliteAlpha
        val cal = satelliteHistoryManager?.getCalibrationForEpoch(currentSatelliteEpoch.id)
        val shiftE = cal?.shiftEastMeters ?: 0.0
        val shiftN = cal?.shiftNorthMeters ?: 0.0
        val scaleMult = cal?.scaleMultiplier ?: 1.0
        val rotRad = Math.toRadians(cal?.rotationDeg ?: 0.0)
        val cosR = cos(rotRad)
        val sinR = sin(rotRad)

        val sub = SatelliteHistoryManager.MESH_SUBDIVISIONS
        for (tile in activeSatelliteTiles) {
            if (tile.bitmap.isRecycled) continue
            val mesh = tile.vn2000MeshCoords
            val vertexPairs = mesh.size / 2
            for (i in 0 until vertexPairs) {
                var east = mesh[i * 2]
                var north = mesh[i * 2 + 1]

                if (scaleMult != 1.0 || rotRad != 0.0) {
                    val dE = (east - centerEast) * scaleMult
                    val dN = (north - centerNorth) * scaleMult
                    east = centerEast + dE * cosR - dN * sinR
                    north = centerNorth + dE * sinR + dN * cosR
                }

                east += shiftE
                north += shiftN
                reusableMeshScreenVerts[i * 2] = worldEastToScreenX(east)
                reusableMeshScreenVerts[i * 2 + 1] = worldNorthToScreenY(north)
            }
            canvas.drawBitmapMesh(
                tile.bitmap,
                sub,
                sub,
                reusableMeshScreenVerts,
                0,
                null,
                0,
                satellitePaint
            )
        }

        for (photo in customGooglePhotos) {
            if (photo.bitmap.isRecycled) continue
            if (!photo.boundsVn2000.intersects(visibleBounds)) continue
            reusableDestRect.set(
                worldEastToScreenX(photo.boundsVn2000.minEast),
                worldNorthToScreenY(photo.boundsVn2000.maxNorth),
                worldEastToScreenX(photo.boundsVn2000.maxEast),
                worldNorthToScreenY(photo.boundsVn2000.minNorth)
            )
            canvas.drawBitmap(photo.bitmap, null, reusableDestRect, satellitePaint)
            if (isCalibrationDragMode) {
                canvas.drawRect(reusableDestRect, customPhotoBorderPaint)
            }
        }
    }

    private fun drawGeoTiffLayer(canvas: Canvas, visibleBounds: GisFileLoader.WorldBounds) {
        val dataset = geoTiffDataset ?: return
        if (!dataset.bounds.intersects(visibleBounds)) return

        dataset.overviewBitmap?.let { overview ->
            if (!overview.isRecycled) {
                reusableDestRect.set(
                    worldEastToScreenX(dataset.bounds.minEast),
                    worldNorthToScreenY(dataset.bounds.maxNorth),
                    worldEastToScreenX(dataset.bounds.maxEast),
                    worldNorthToScreenY(dataset.bounds.minNorth)
                )
                canvas.drawBitmap(overview, null, reusableDestRect, rasterPaint)
            }
        }

        val tileBmp = activeTileBitmap
        val tileBnds = activeTileBounds
        if (tileBmp != null && !tileBmp.isRecycled && tileBnds != null && tileBnds.intersects(visibleBounds)) {
            reusableDestRect.set(
                worldEastToScreenX(tileBnds.minEast),
                worldNorthToScreenY(tileBnds.maxNorth),
                worldEastToScreenX(tileBnds.maxEast),
                worldNorthToScreenY(tileBnds.minNorth)
            )
            canvas.drawBitmap(tileBmp, null, reusableDestRect, rasterPaint)
        }
    }

    private fun drawDgnVectorLayer(canvas: Canvas, visibleBounds: GisFileLoader.WorldBounds): Int {
        val layer = dgnLayerData ?: return 0
        var drawnCount = 0

        val useSimplifiedLod = pixelsPerMeter < 0.85
        val drawTextLabels = pixelsPerMeter >= 0.45

        for (entity in layer.entities) {
            if (!entity.bounds.intersects(visibleBounds)) continue

            if (entity.type != GisFileLoader.VectorGeometryType.POINT &&
                entity.type != GisFileLoader.VectorGeometryType.TEXT
            ) {
                val screenSpanPx = max(entity.bounds.widthEast, entity.bounds.heightNorth) * pixelsPerMeter
                if (screenSpanPx < 1.2) continue
            }

            val coords = if (useSimplifiedLod) entity.simplifiedCoordinates else entity.coordinates
            val ptCount = coords.size / 2
            if (ptCount == 0) continue

            when (entity.type) {
                GisFileLoader.VectorGeometryType.POINT -> {
                    val sx = worldEastToScreenX(coords[0])
                    val sy = worldNorthToScreenY(coords[1])
                    pointPaint.color = entity.colorInt
                    canvas.drawCircle(sx, sy, max(4.5f, entity.strokeWidthPx), pointPaint)
                    if (drawTextLabels && !entity.textContent.isNullOrBlank()) {
                        textPaint.color = entity.colorInt
                        textPaint.textSize = 24f
                        canvas.drawText(entity.textContent, sx + 8f, sy - 8f, textPaint)
                    }
                    drawnCount++
                }
                GisFileLoader.VectorGeometryType.TEXT -> {
                    if (drawTextLabels && !entity.textContent.isNullOrBlank()) {
                        val sx = worldEastToScreenX(coords[0])
                        val sy = worldNorthToScreenY(coords[1])
                        textPaint.color = entity.colorInt
                        textPaint.textSize = (18f + (pixelsPerMeter.toFloat() * 2.5f)).coerceIn(18f, 34f)
                        canvas.drawText(entity.textContent, sx, sy, textPaint)
                        drawnCount++
                    }
                }
                GisFileLoader.VectorGeometryType.LINE -> {
                    if (ptCount >= 2) {
                        vectorStrokePaint.color = entity.colorInt
                        vectorStrokePaint.strokeWidth = entity.strokeWidthPx
                        canvas.drawLine(
                            worldEastToScreenX(coords[0]),
                            worldNorthToScreenY(coords[1]),
                            worldEastToScreenX(coords[2]),
                            worldNorthToScreenY(coords[3]),
                            vectorStrokePaint
                        )
                        drawnCount++
                    }
                }
                GisFileLoader.VectorGeometryType.POLYLINE -> {
                    if (ptCount >= 2) {
                        reusablePath.reset()
                        reusablePath.moveTo(
                            worldEastToScreenX(coords[0]),
                            worldNorthToScreenY(coords[1])
                        )
                        for (i in 1 until ptCount) {
                            reusablePath.lineTo(
                                worldEastToScreenX(coords[i * 2]),
                                worldNorthToScreenY(coords[i * 2 + 1])
                            )
                        }
                        vectorStrokePaint.color = entity.colorInt
                        vectorStrokePaint.strokeWidth = entity.strokeWidthPx
                        canvas.drawPath(reusablePath, vectorStrokePaint)
                        drawnCount++
                    }
                }
                GisFileLoader.VectorGeometryType.POLYGON -> {
                    if (ptCount >= 3) {
                        reusablePath.reset()
                        reusablePath.moveTo(
                            worldEastToScreenX(coords[0]),
                            worldNorthToScreenY(coords[1])
                        )
                        for (i in 1 until ptCount) {
                            reusablePath.lineTo(
                                worldEastToScreenX(coords[i * 2]),
                                worldNorthToScreenY(coords[i * 2 + 1])
                            )
                        }
                        reusablePath.close()

                        polygonFillPaint.color = Color.argb(
                            38,
                            Color.red(entity.colorInt),
                            Color.green(entity.colorInt),
                            Color.blue(entity.colorInt)
                        )
                        canvas.drawPath(reusablePath, polygonFillPaint)

                        vectorStrokePaint.color = entity.colorInt
                        vectorStrokePaint.strokeWidth = entity.strokeWidthPx
                        canvas.drawPath(reusablePath, vectorStrokePaint)
                        drawnCount++
                    }
                }
            }
        }
        return drawnCount
    }

    private fun drawVn2000CoordinateGrid(canvas: Canvas, visibleBounds: GisFileLoader.WorldBounds) {
        val rawStep = (220.0 / pixelsPerMeter).coerceAtLeast(1.0)
        val exponent = kotlin.math.floor(log10(rawStep))
        val fraction = rawStep / 10.0.pow(exponent)
        val niceFraction = when {
            fraction < 1.5 -> 1.0
            fraction < 3.5 -> 2.0
            fraction < 7.5 -> 5.0
            else -> 10.0
        }
        val gridStepMeters = niceFraction * 10.0.pow(exponent)

        val startEast = kotlin.math.floor(visibleBounds.minEast / gridStepMeters) * gridStepMeters
        var east = startEast
        while (east <= visibleBounds.maxEast) {
            val sx = worldEastToScreenX(east)
            canvas.drawLine(sx, 0f, sx, height.toFloat(), gridPaint)
            canvas.drawText(
                String.format(Locale.US, "Y:%.0f", east),
                sx + 6f,
                height - 18f,
                gridLabelPaint
            )
            east += gridStepMeters
        }

        val startNorth = kotlin.math.floor(visibleBounds.minNorth / gridStepMeters) * gridStepMeters
        var north = startNorth
        while (north <= visibleBounds.maxNorth) {
            val sy = worldNorthToScreenY(north)
            canvas.drawLine(0f, sy, width.toFloat(), sy, gridPaint)
            canvas.drawText(
                String.format(Locale.US, "X:%.0f", north),
                12f,
                sy - 6f,
                gridLabelPaint
            )
            north += gridStepMeters
        }
    }

    /**
     * Vẽ biểu tượng Vị trí hiện tại (GPS Blue Dot kèm vòng tròn bán kính sai số tính bằng mét thực tế).
     */
    private fun drawCurrentGpsLocationMarker(canvas: Canvas) {
        val east = gpsEastVn2000 ?: return
        val north = gpsNorthVn2000 ?: return
        val sx = worldEastToScreenX(east)
        val sy = worldNorthToScreenY(north)

        val accuracyRadiusPx = (gpsAccuracyMeters * pixelsPerMeter).toFloat().coerceIn(14f, 280f)
        canvas.drawCircle(sx, sy, accuracyRadiusPx, gpsAccuracyFillPaint)
        canvas.drawCircle(sx, sy, accuracyRadiusPx, gpsAccuracyStrokePaint)

        canvas.drawCircle(sx, sy, 14f, gpsHaloPaint)
        canvas.drawCircle(sx, sy, 10f, gpsCenterDotPaint)

        textPaint.color = Color.rgb(56, 189, 248)
        textPaint.textSize = 23f
        canvas.drawText(
            String.format(Locale.US, "Vị trí của tôi (±%.1fm)", gpsAccuracyMeters),
            sx + 18f,
            sy - 14f,
            textPaint
        )
    }

    private fun drawTappedCrosshair(canvas: Canvas) {
        val east = tappedEast ?: return
        val north = tappedNorth ?: return
        val sx = worldEastToScreenX(east)
        val sy = worldNorthToScreenY(north)
        val radius = 18f
        canvas.drawCircle(sx, sy, radius, crosshairPaint)
        canvas.drawLine(sx - radius * 1.6f, sy, sx + radius * 1.6f, sy, crosshairPaint)
        canvas.drawLine(sx, sy - radius * 1.6f, sx, sy + radius * 1.6f, crosshairPaint)
    }

    private fun findNearestVectorEntityDescription(east: Double, north: Double): String? {
        val layer = dgnLayerData ?: return null
        val searchRadiusMeters = 18.0 / pixelsPerMeter
        val tapBox = GisFileLoader.WorldBounds(
            minEast = east - searchRadiusMeters,
            minNorth = north - searchRadiusMeters,
            maxEast = east + searchRadiusMeters,
            maxNorth = north + searchRadiusMeters
        )

        val hit = layer.entities.firstOrNull { it.bounds.intersects(tapBox) } ?: return null
        val labelPart = if (!hit.textContent.isNullOrBlank()) " | Nhãn: ${hit.textContent}" else ""
        return "DGN Level ${hit.level} (${hit.type.name})$labelPart"
    }

    private fun worldEastToScreenX(worldEast: Double): Float {
        return ((worldEast - centerEast) * pixelsPerMeter + width * 0.5).toFloat()
    }

    private fun worldNorthToScreenY(worldNorth: Double): Float {
        return ((centerNorth - worldNorth) * pixelsPerMeter + height * 0.5).toFloat()
    }

    private fun screenXToWorldEast(screenX: Float): Double {
        return centerEast + (screenX - width * 0.5) / pixelsPerMeter
    }

    private fun screenYToWorldNorth(screenY: Float): Double {
        return centerNorth - (screenY - height * 0.5) / pixelsPerMeter
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        viewScope.cancel()
    }
}