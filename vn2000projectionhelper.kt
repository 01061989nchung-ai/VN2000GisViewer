package vn.gis.vn2000viewer.crs

import org.locationtech.proj4j.CRSFactory
import org.locationtech.proj4j.CoordinateReferenceSystem
import org.locationtech.proj4j.CoordinateTransformFactory
import org.locationtech.proj4j.ProjCoordinate
import kotlin.math.abs

/**
 * Bộ quản lý Hệ quy chiếu và Hệ tọa độ Quốc gia VN-2000.
 *
 * Hỗ trợ:
 * 1. Danh sách đầy đủ kinh tuyến trục (L0) của 63 tỉnh/thành phố Việt Nam
 *    theo Phụ lục 02 - Thông tư 25/2014/TT-BTNMT của Bộ Tài nguyên và Môi trường
 *    (Nguồn: https://thuvienphapluat.vn/van-ban/Bat-dong-san/Thong-tu-25-2014-TT-BTNMT-ban-do-dia-chinh-236562.aspx
 *    và https://tracdiahoangphat.com/kinh-tuyen-truc-cac-tinh/).
 * 2. Chuỗi định nghĩa PROJ.4 chuẩn VN-2000 cho cả Múi chiếu 3 độ (k0 = 0.9999)
 *    và Múi chiếu 6 độ (k0 = 0.9996) kèm 7 tham số tính chuyển Bursa-Wolf (Helmert)
 *    sang WGS84 theo Quyết định 05/2007/QĐ-BTNMT / EPSG:6960
 *    (Nguồn: https://epsg.io/4756-6960 và https://bandolamnghiep.com/he-toa-do-vn2000-qgis-epsg/).
 * 3. Chuyển đổi tọa độ hai chiều giữa VN-2000 (X Bắc, Y Đông) và WGS84 (Lat, Lon),
 *    cũng như chuyển đổi giữa các múi chiếu VN-2000 khác nhau để chồng lớp DGN V8 & GeoTIFF.
 */
object VN2000ProjectionHelper {

    /**
     * 7 tham số chuyển đổi chuẩn từ VN-2000 sang WGS84 toàn cầu (Decision 05/2007/QĐ-BTNMT - EPSG:6960):
     * dX = -191.90441429 m, dY = -39.30318279 m, dZ = -111.45032835 m
     * rX = -0.00928836", rY = 0.01975479", rZ = -0.00427372"
     * dS = 0.252906278 ppm
     */
    const val TOWGS84_VN2000_STANDARD =
        "-191.90441429,-39.30318279,-111.45032835,-0.00928836,0.01975479,-0.00427372,0.252906278"

    /**
     * Bộ 7 tham số thay thế khu vực Đồng bằng sông Cửu Long (EPSG:5194) khi cần đối chiếu dữ liệu cũ.
     */
    const val TOWGS84_VN2000_MEKONG =
        "-192.873,-39.382,-111.202,0.00205,0.0005,-0.00335,0.0188"

    /**
     * Loại múi chiếu trong hệ tọa độ phẳng VN-2000.
     * - Múi 3 độ (TM-3): Hệ số biến dạng tỷ lệ k0 = 0.9999 (dùng cho bản đồ tỷ lệ lớn 1:2.000 - 1:10.000).
     * - Múi 6 độ (UTM): Hệ số biến dạng tỷ lệ k0 = 0.9996 (dùng cho bản đồ tỷ lệ trung bình và nhỏ 1:25.000 - 1:500.000).
     */
    enum class ZoneWidth(val degrees: Int, val scaleFactor: Double, val label: String) {
        ZONE_3_DEG(3, 0.9999, "Múi 3° (k0 = 0.9999 - Bản đồ địa chính/lâm nghiệp)"),
        ZONE_6_DEG(6, 0.9996, "Múi 6° (k0 = 0.9996 - Bản đồ cơ bản/quốc gia)")
    }

    /**
     * Thông tin Kinh tuyến trục của từng Tỉnh/Thành phố.
     * @param id Số thứ tự từ 1 đến 63
     * @param provinceName Tên tỉnh/thành phố
     * @param centralMeridianDeg Kinh tuyến trục L0 tính bằng độ thập phân (ví dụ: 105°45' = 105.75)
     * @param dmsText Chuỗi hiển thị độ-phút (ví dụ: "105°45'")
     * @param epsgTm3 Mã EPSG tương ứng cho múi chiếu 3 độ
     */
    data class ProvinceMeridian(
        val id: Int,
        val provinceName: String,
        val centralMeridianDeg: Double,
        val dmsText: String,
        val epsgTm3: Int
    ) {
        override fun toString(): String = "$provinceName ($dmsText - EPSG:$epsgTm3)"
    }

    /**
     * Tọa độ phẳng VN-2000 theo đúng quy ước Trắc địa Việt Nam:
     * - [xNorth]: Trục X hướng Bắc (Northing - 7 chữ số, đơn vị mét)
     * - [yEast]: Trục Y hướng Đông (Easting - 6 chữ số, đơn vị mét)
     */
    data class VN2000Coordinate(
        val xNorth: Double,
        val yEast: Double,
        val centralMeridian: Double,
        val zoneWidth: ZoneWidth
    )

    /**
     * Tọa độ Địa lý WGS84 (Vĩ độ, Kinh độ).
     */
    data class Wgs84Coordinate(
        val latitude: Double,
        val longitude: Double
    )

    private val crsFactory = CRSFactory()
    private val transformFactory = CoordinateTransformFactory()
    private val crsCache = HashMap<String, CoordinateReferenceSystem>()

    /**
     * Danh sách đầy đủ 63 tỉnh/thành phố Việt Nam cùng Kinh tuyến trục và mã EPSG TM-3
     * theo Phụ lục 02 Thông tư 25/2014/TT-BTNMT.
     */
    val PROVINCE_MERIDIANS: List<ProvinceMeridian> = listOf(
        ProvinceMeridian(1, "An Giang", 104.75, "104°45'", 9208),
        ProvinceMeridian(2, "Bà Rịa - Vũng Tàu", 107.75, "107°45'", 5899),
        ProvinceMeridian(3, "Bắc Giang", 107.00, "107°00'", 9214),
        ProvinceMeridian(4, "Bắc Kạn", 106.50, "106°30'", 9213),
        ProvinceMeridian(5, "Bạc Liêu", 105.00, "105°00'", 5897),
        ProvinceMeridian(6, "Bắc Ninh", 105.50, "105°30'", 9209),
        ProvinceMeridian(7, "Bến Tre", 105.75, "105°45'", 9210),
        ProvinceMeridian(8, "Bình Định", 108.25, "108°15'", 9217),
        ProvinceMeridian(9, "Bình Dương", 105.75, "105°45'", 9210),
        ProvinceMeridian(10, "Bình Phước", 106.25, "106°15'", 9212),
        ProvinceMeridian(11, "Bình Thuận", 108.50, "108°30'", 9218),
        ProvinceMeridian(12, "Cà Mau", 104.50, "104°30'", 9207),
        ProvinceMeridian(13, "TP. Cần Thơ", 105.00, "105°00'", 5897),
        ProvinceMeridian(14, "Cao Bằng", 105.75, "105°45'", 9210),
        ProvinceMeridian(15, "TP. Đà Nẵng", 107.75, "107°45'", 5899),
        ProvinceMeridian(16, "Đắk Lắk", 108.50, "108°30'", 9218),
        ProvinceMeridian(17, "Đắk Nông", 108.50, "108°30'", 9218),
        ProvinceMeridian(18, "Điện Biên", 103.00, "103°00'", 9205),
        ProvinceMeridian(19, "Đồng Nai", 107.75, "107°45'", 5899),
        ProvinceMeridian(20, "Đồng Tháp", 105.00, "105°00'", 5897),
        ProvinceMeridian(21, "Gia Lai", 108.50, "108°30'", 9218),
        ProvinceMeridian(22, "Hà Giang", 105.50, "105°30'", 9209),
        ProvinceMeridian(23, "Hà Nam", 105.00, "105°00'", 5897),
        ProvinceMeridian(24, "TP. Hà Nội", 105.00, "105°00'", 5897),
        ProvinceMeridian(25, "Hà Tĩnh", 105.50, "105°30'", 9209),
        ProvinceMeridian(26, "Hải Dương", 105.50, "105°30'", 9209),
        ProvinceMeridian(27, "TP. Hải Phòng", 105.75, "105°45'", 9210),
        ProvinceMeridian(28, "Hậu Giang", 105.00, "105°00'", 5897),
        ProvinceMeridian(29, "Hòa Bình", 106.00, "106°00'", 9211),
        ProvinceMeridian(30, "Hưng Yên", 105.50, "105°30'", 9209),
        ProvinceMeridian(31, "Khánh Hòa", 108.25, "108°15'", 9217),
        ProvinceMeridian(32, "Kiên Giang", 104.50, "104°30'", 9207),
        ProvinceMeridian(33, "Kon Tum", 107.50, "107°30'", 9216),
        ProvinceMeridian(34, "Lai Châu", 103.00, "103°00'", 9205),
        ProvinceMeridian(35, "Lâm Đồng", 107.75, "107°45'", 5899),
        ProvinceMeridian(36, "Lạng Sơn", 107.25, "107°15'", 9215),
        ProvinceMeridian(37, "Lào Cai", 104.75, "104°45'", 9208),
        ProvinceMeridian(38, "Long An", 105.75, "105°45'", 9210),
        ProvinceMeridian(39, "Nam Định", 105.50, "105°30'", 9209),
        ProvinceMeridian(40, "Nghệ An", 104.75, "104°45'", 9208),
        ProvinceMeridian(41, "Ninh Bình", 105.00, "105°00'", 5897),
        ProvinceMeridian(42, "Ninh Thuận", 108.25, "108°15'", 9217),
        ProvinceMeridian(43, "Phú Thọ", 104.75, "104°45'", 9208),
        ProvinceMeridian(44, "Phú Yên", 108.50, "108°30'", 9218),
        ProvinceMeridian(45, "Quảng Bình", 106.00, "106°00'", 9211),
        ProvinceMeridian(46, "Quảng Nam", 107.75, "107°45'", 5899),
        ProvinceMeridian(47, "Quảng Ngãi", 108.00, "108°00'", 5898),
        ProvinceMeridian(48, "Quảng Ninh", 107.75, "107°45'", 5899),
        ProvinceMeridian(49, "Quảng Trị", 106.25, "106°15'", 9212),
        ProvinceMeridian(50, "Sóc Trăng", 105.50, "105°30'", 9209),
        ProvinceMeridian(51, "Sơn La", 104.00, "104°00'", 9206),
        ProvinceMeridian(52, "Tây Ninh", 105.50, "105°30'", 9209),
        ProvinceMeridian(53, "Thái Bình", 105.50, "105°30'", 9209),
        ProvinceMeridian(54, "Thái Nguyên", 106.50, "106°30'", 9213),
        ProvinceMeridian(55, "Thanh Hóa", 105.00, "105°00'", 5897),
        ProvinceMeridian(56, "Thừa Thiên Huế", 107.00, "107°00'", 9214),
        ProvinceMeridian(57, "Tiền Giang", 105.75, "105°45'", 9210),
        ProvinceMeridian(58, "TP. Hồ Chí Minh", 105.75, "105°45'", 9210),
        ProvinceMeridian(59, "Trà Vinh", 105.50, "105°30'", 9209),
        ProvinceMeridian(60, "Tuyên Quang", 106.00, "106°00'", 9211),
        ProvinceMeridian(61, "Vĩnh Long", 105.50, "105°30'", 9209),
        ProvinceMeridian(62, "Vĩnh Phúc", 105.00, "105°00'", 5897),
        ProvinceMeridian(63, "Yên Bái", 104.75, "104°45'", 9208)
    )

    /**
     * Tạo chuỗi định nghĩa PROJ.4 chuẩn cho hệ tọa độ phẳng VN-2000.
     *
     * @param centralMeridian Kinh tuyến trục L0 (ví dụ: 105.75 cho TP.HCM, 105.0 cho Hà Nội)
     * @param zoneWidth Múi chiếu 3° (k=0.9999) hoặc 6° (k=0.9996)
     * @param falseEasting Giá trị dịch chuyển trục Đông (mặc định 500000.0 m)
     * @param falseNorthing Giá trị dịch chuyển trục Bắc (mặc định 0.0 m)
     * @param towgs84 Chuỗi 7 tham số chuyển đổi sang WGS84
     */
    fun buildProj4String(
        centralMeridian: Double,
        zoneWidth: ZoneWidth = ZoneWidth.ZONE_3_DEG,
        falseEasting: Double = 500000.0,
        falseNorthing: Double = 0.0,
        towgs84: String = TOWGS84_VN2000_STANDARD
    ): String {
        return "+proj=tmerc +lat_0=0 +lon_0=$centralMeridian +k=${zoneWidth.scaleFactor} " +
            "+x_0=$falseEasting +y_0=$falseNorthing +ellps=WGS84 " +
            "+towgs84=$towgs84 +units=m +no_defs"
    }

    /**
     * Chuỗi PROJ.4 cho hệ tọa độ Địa lý WGS84 (EPSG:4326).
     */
    const val PROJ4_WGS84 = "+proj=longlat +datum=WGS84 +no_defs"

    /**
     * Chuỗi PROJ.4 cho hệ tọa độ Địa lý VN-2000 (EPSG:4756).
     */
    const val PROJ4_VN2000_GEOG =
        "+proj=longlat +ellps=WGS84 +towgs84=$TOWGS84_VN2000_STANDARD +no_defs"

    @Synchronized
    fun getVn2000Crs(
        centralMeridian: Double,
        zoneWidth: ZoneWidth = ZoneWidth.ZONE_3_DEG
    ): CoordinateReferenceSystem {
        val key = "VN2000_${centralMeridian}_${zoneWidth.degrees}"
        return crsCache.getOrPut(key) {
            val proj4 = buildProj4String(centralMeridian, zoneWidth)
            crsFactory.createFromParameters(key, proj4)
        }
    }

    @Synchronized
    fun getWgs84Crs(): CoordinateReferenceSystem {
        return crsCache.getOrPut("EPSG:4326") {
            crsFactory.createFromParameters("EPSG:4326", PROJ4_WGS84)
        }
    }

    /**
     * Chuyển đổi tọa độ phẳng VN-2000 (X Bắc, Y Đông) sang tọa độ Địa lý WGS84 (Latitude, Longitude).
     * Lưu ý: Trong Proj4J, trục x là Easting (Y Đông) và trục y là Northing (X Bắc).
     */
    fun vn2000ToWgs84(
        xNorth: Double,
        yEast: Double,
        centralMeridian: Double,
        zoneWidth: ZoneWidth = ZoneWidth.ZONE_3_DEG
    ): Wgs84Coordinate {
        val srcCrs = getVn2000Crs(centralMeridian, zoneWidth)
        val dstCrs = getWgs84Crs()
        val transform = transformFactory.createTransform(srcCrs, dstCrs)
        val srcCoord = ProjCoordinate(yEast, xNorth)
        val dstCoord = ProjCoordinate()
        transform.transform(srcCoord, dstCoord)
        return Wgs84Coordinate(latitude = dstCoord.y, longitude = dstCoord.x)
    }

    /**
     * Chuyển đổi tọa độ Địa lý WGS84 (Latitude, Longitude) sang tọa độ phẳng VN-2000 (X Bắc, Y Đông).
     */
    fun wgs84ToVn2000(
        latitude: Double,
        longitude: Double,
        centralMeridian: Double,
        zoneWidth: ZoneWidth = ZoneWidth.ZONE_3_DEG
    ): VN2000Coordinate {
        val srcCrs = getWgs84Crs()
        val dstCrs = getVn2000Crs(centralMeridian, zoneWidth)
        val transform = transformFactory.createTransform(srcCrs, dstCrs)
        val srcCoord = ProjCoordinate(longitude, latitude)
        val dstCoord = ProjCoordinate()
        transform.transform(srcCoord, dstCoord)
        return VN2000Coordinate(
            xNorth = dstCoord.y,
            yEast = dstCoord.x,
            centralMeridian = centralMeridian,
            zoneWidth = zoneWidth
        )
    }

    /**
     * Chuyển đổi tọa độ phẳng VN-2000 từ một kinh tuyến trục / múi chiếu nguồn sang kinh tuyến trục / múi chiếu đích.
     * Dùng khi chồng lớp file GeoTIFF (ví dụ ở múi 6° L0=105° - EPSG:3405) với file DGN V8 ở múi 3° địa phương.
     */
    fun transformBetweenVn2000Zones(
        xNorth: Double,
        yEast: Double,
        srcMeridian: Double,
        srcZoneWidth: ZoneWidth,
        dstMeridian: Double,
        dstZoneWidth: ZoneWidth
    ): VN2000Coordinate {
        if (abs(srcMeridian - dstMeridian) < 1e-6 && srcZoneWidth == dstZoneWidth) {
            return VN2000Coordinate(xNorth, yEast, dstMeridian, dstZoneWidth)
        }
        val srcCrs = getVn2000Crs(srcMeridian, srcZoneWidth)
        val dstCrs = getVn2000Crs(dstMeridian, dstZoneWidth)
        val transform = transformFactory.createTransform(srcCrs, dstCrs)
        val srcCoord = ProjCoordinate(yEast, xNorth)
        val dstCoord = ProjCoordinate()
        transform.transform(srcCoord, dstCoord)
        return VN2000Coordinate(
            xNorth = dstCoord.y,
            yEast = dstCoord.x,
            centralMeridian = dstMeridian,
            zoneWidth = dstZoneWidth
        )
    }

    /**
     * Tự động nhận diện Kinh tuyến trục và Múi chiếu từ chuỗi WKT hoặc PROJ của GeoTIFF/DGN.
     */
    fun detectMeridianFromWktOrProj(crsText: String?, fallbackProvince: ProvinceMeridian): Pair<Double, ZoneWidth> {
        if (crsText.isNullOrBlank()) {
            return Pair(fallbackProvince.centralMeridianDeg, ZoneWidth.ZONE_3_DEG)
        }
        val centralMeridianRegex = Regex("""(?:central_meridian["\s,]+|lon_0=)([0-9]+(?:\.[0-9]+)?)""", RegexOption.IGNORE_CASE)
        val scaleFactorRegex = Regex("""(?:scale_factor["\s,]+|k=|k_0=)(0\.999[69])""", RegexOption.IGNORE_CASE)

        val lon0 = centralMeridianRegex.find(crsText)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
            ?: fallbackProvince.centralMeridianDeg
        val k0 = scaleFactorRegex.find(crsText)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
            ?: ZoneWidth.ZONE_3_DEG.scaleFactor

        val zoneWidth = if (abs(k0 - 0.9996) < 0.0001) ZoneWidth.ZONE_6_DEG else ZoneWidth.ZONE_3_DEG
        return Pair(lon0, zoneWidth)
    }

    /**
     * Tìm tỉnh/thành phố theo tên hoặc lấy mặc định TP. Hồ Chí Minh (105°45').
     */
    fun findProvinceByName(name: String): ProvinceMeridian {
        return PROVINCE_MERIDIANS.firstOrNull {
            it.provinceName.contains(name, ignoreCase = true)
        } ?: PROVINCE_MERIDIANS.first { it.provinceName.contains("Hồ Chí Minh") }
    }
}