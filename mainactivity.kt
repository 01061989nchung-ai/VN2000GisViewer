package vn.gis.vn2000viewer

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.provider.OpenableColumns
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import vn.gis.vn2000viewer.crs.VN2000ProjectionHelper
import vn.gis.vn2000viewer.data.GisFileLoader
import vn.gis.vn2000viewer.data.SatelliteHistoryManager
import vn.gis.vn2000viewer.databinding.ActivityMainBinding
import vn.gis.vn2000viewer.ui.MapCanvasView
import java.util.Locale

/**
 * Màn hình chính của ứng dụng đọc, hiển thị và chồng lớp bản đồ MicroStation DGN V8, GeoTIFF,
 * Theo dõi Ảnh Vệ tinh Google Earth các năm (kéo tham chiếu về hệ tọa độ VN-2000) và Định vị GPS Hiện tại.
 */
class MainActivity : AppCompatActivity(), MapCanvasView.OnMapInteractionListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var gisFileLoader: GisFileLoader
    private lateinit var satelliteHistoryManager: SatelliteHistoryManager
    private lateinit var locationManager: LocationManager
    private var loadingJob: Job? = null

    private var selectedProvince: VN2000ProjectionHelper.ProvinceMeridian =
        VN2000ProjectionHelper.findProvinceByName("Lâm Đồng")
    private var selectedZoneWidth: VN2000ProjectionHelper.ZoneWidth =
        VN2000ProjectionHelper.ZoneWidth.ZONE_3_DEG

    private var availableEpochs: List<SatelliteHistoryManager.SatelliteEpoch> =
        SatelliteHistoryManager.DEFAULT_EPOCHS

    private var isTrackingGps: Boolean = false
    private var shouldCenterOnNextFix: Boolean = false

    private val gpsLocationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            handleNewGpsLocation(location, moveCamera = shouldCenterOnNextFix)
            shouldCenterOnNextFix = false
        }

        override fun onProviderEnabled(provider: String) = Unit
        override fun onProviderDisabled(provider: String) = Unit
    }

    // Xin quyền truy cập GPS (ACCESS_FINE_LOCATION / ACCESS_COARSE_LOCATION)
    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarseGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (fineGranted || coarseGranted) {
            startOrCenterOnMyLocation()
        } else {
            Toast.makeText(
                this,
                "Cần cấp quyền Vị trí (GPS) để xác định tọa độ VN-2000 ngoài thực địa.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private val pickDgnLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            handleSelectedDgnUri(uri)
        }
    }

    private val pickGeoTiffLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            handleSelectedGeoTiffUri(uri)
        }
    }

    private val pickGooglePhotoLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            handleSelectedGoogleEarthPhotoUri(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        gisFileLoader = GisFileLoader(applicationContext)
        satelliteHistoryManager = SatelliteHistoryManager(applicationContext)
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager

        binding.mapCanvasView.gisFileLoader = gisFileLoader
        binding.mapCanvasView.satelliteHistoryManager = satelliteHistoryManager
        binding.mapCanvasView.interactionListener = this

        setupProvinceMeridianDropdown()
        setupZoneWidthSelector()
        setupSatelliteHistoryControls()
        setupActionButtons()
        updateProj4InfoBanner()
        refreshDynamicWaybackEpochs()
    }

    private fun setupProvinceMeridianDropdown() {
        val provinces = VN2000ProjectionHelper.PROVINCE_MERIDIANS
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            provinces
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        binding.spinnerProvince.adapter = adapter

        val defaultIndex = provinces.indexOfFirst { it.provinceName.contains("Lâm Đồng") }
            .coerceAtLeast(0)
        binding.spinnerProvince.setSelection(defaultIndex)

        binding.spinnerProvince.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedProvince = provinces[position]
                binding.mapCanvasView.setProjectionConfig(selectedProvince, selectedZoneWidth)
                updateProj4InfoBanner()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun setupZoneWidthSelector() {
        binding.radioGroupZone.setOnCheckedChangeListener { _, checkedId ->
            selectedZoneWidth = if (checkedId == R.id.radioZone6Deg) {
                VN2000ProjectionHelper.ZoneWidth.ZONE_6_DEG
            } else {
                VN2000ProjectionHelper.ZoneWidth.ZONE_3_DEG
            }
            binding.mapCanvasView.setProjectionConfig(selectedProvince, selectedZoneWidth)
            updateProj4InfoBanner()
        }
    }

    private fun setupSatelliteHistoryControls() {
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            availableEpochs
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        binding.spinnerSatelliteYear.adapter = adapter

        binding.spinnerSatelliteYear.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position in availableEpochs.indices) {
                    val epoch = availableEpochs[position]
                    binding.mapCanvasView.setSatelliteEpoch(epoch)
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        binding.btnPrevYear.setOnClickListener {
            val nextPos = (binding.spinnerSatelliteYear.selectedItemPosition + 1)
                .coerceAtMost(availableEpochs.size - 1)
            binding.spinnerSatelliteYear.setSelection(nextPos)
        }

        binding.btnNextYear.setOnClickListener {
            val prevPos = (binding.spinnerSatelliteYear.selectedItemPosition - 1)
                .coerceAtLeast(0)
            binding.spinnerSatelliteYear.setSelection(prevPos)
        }

        binding.seekBarSatOpacity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                binding.mapCanvasView.satelliteAlpha = progress
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        binding.switchDragCalibrate.setOnCheckedChangeListener { _, isChecked ->
            binding.mapCanvasView.isCalibrationDragMode = isChecked
            val msg = if (isChecked) {
                "Đã BẬT chế độ Kéo tham chiếu: Vuốt/chụm trên bản đồ để dịch chuyển ảnh Google khớp vào ranh DGN VN-2000."
            } else {
                "Đã KHÓA vị trí tham chiếu ảnh Google Earth."
            }
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }

        binding.btnResetCalibration.setOnClickListener {
            binding.mapCanvasView.resetCurrentCalibration()
            Toast.makeText(this, "Đã đặt lại độ lệch tham chiếu (ΔX=0, ΔY=0).", Toast.LENGTH_SHORT).show()
        }
    }

    private fun refreshDynamicWaybackEpochs() {
        lifecycleScope.launch {
            val fetched = satelliteHistoryManager.fetchAvailableWaybackEpochs()
            if (fetched.size > 2) {
                availableEpochs = fetched
                val adapter = ArrayAdapter(
                    this@MainActivity,
                    android.R.layout.simple_spinner_item,
                    availableEpochs
                ).apply {
                    setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                }
                binding.spinnerSatelliteYear.adapter = adapter
            }
        }
    }

    private fun setupActionButtons() {
        binding.btnPickDgn.setOnClickListener {
            pickDgnLauncher.launch(arrayOf("*/*"))
        }

        binding.btnPickGeoTiff.setOnClickListener {
            pickGeoTiffLauncher.launch(arrayOf("image/tiff", "image/x-tiff", "application/octet-stream", "*/*"))
        }

        binding.btnPickGooglePhoto.setOnClickListener {
            pickGooglePhotoLauncher.launch(arrayOf("image/*"))
        }

        binding.btnFitBounds.setOnClickListener {
            binding.mapCanvasView.fitToActiveLayers()
        }

        // Nút "Vị trí hiện tại" (GPS -> VN-2000)
        binding.btnMyLocation.setOnClickListener {
            checkPermissionAndLocateUser()
        }

        binding.switchLayerSatellite.setOnCheckedChangeListener { _, isChecked ->
            binding.mapCanvasView.isSatelliteLayerVisible = isChecked
        }

        binding.switchLayerDgn.setOnCheckedChangeListener { _, isChecked ->
            binding.mapCanvasView.isDgnLayerVisible = isChecked
        }

        binding.switchLayerTiff.setOnCheckedChangeListener { _, isChecked ->
            binding.mapCanvasView.isGeoTiffLayerVisible = isChecked
        }

        binding.switchGrid.setOnCheckedChangeListener { _, isChecked ->
            binding.mapCanvasView.isGridVisible = isChecked
        }
    }

    private fun checkPermissionAndLocateUser() {
        val fineGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarseGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (fineGranted || coarseGranted) {
            startOrCenterOnMyLocation()
        } else {
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun startOrCenterOnMyLocation() {
        shouldCenterOnNextFix = true
        try {
            val gpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
            val netEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
            if (!gpsEnabled && !netEnabled) {
                Toast.makeText(
                    this,
                    "Vui lòng bật Định vị (GPS) trên điện thoại để lấy tọa độ VN-2000 hiện tại.",
                    Toast.LENGTH_LONG
                ).show()
                return
            }

            // Lấy vị trí gần nhất nếu có sẵn để phản hồi tức thì
            val lastGps = if (gpsEnabled) locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER) else null
            val lastNet = if (netEnabled) locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) else null
            val bestLast = when {
                lastGps != null && lastNet != null -> if (lastGps.time >= lastNet.time) lastGps else lastNet
                lastGps != null -> lastGps
                else -> lastNet
            }
            if (bestLast != null) {
                handleNewGpsLocation(bestLast, moveCamera = true)
                shouldCenterOnNextFix = false
            } else {
                Toast.makeText(this, "Đang thu tín hiệu vệ tinh GPS...", Toast.LENGTH_SHORT).show()
            }

            if (!isTrackingGps) {
                if (gpsEnabled) {
                    locationManager.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER,
                        2000L,
                        1.0f,
                        gpsLocationListener,
                        Looper.getMainLooper()
                    )
                }
                if (netEnabled) {
                    locationManager.requestLocationUpdates(
                        LocationManager.NETWORK_PROVIDER,
                        3000L,
                        2.0f,
                        gpsLocationListener,
                        Looper.getMainLooper()
                    )
                }
                isTrackingGps = true
            }
        } catch (e: SecurityException) {
            Toast.makeText(this, "Lỗi quyền GPS: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleNewGpsLocation(location: Location, moveCamera: Boolean) {
        val vnCoord = binding.mapCanvasView.updateCurrentGpsLocation(
            latitudeWgs84 = location.latitude,
            longitudeWgs84 = location.longitude,
            accuracyMeters = location.accuracy,
            moveCamera = moveCamera
        )
        val hitEntity = binding.mapCanvasView.findEntityAtCoordinate(vnCoord.yEast, vnCoord.xNorth)
        val entityPart = if (hitEntity != null) "\nĐang đứng tại: $hitEntity" else ""
        binding.tvCoordinateOutput.text = String.format(
            Locale.US,
            "GPS HIỆN TẠI -> VN-2000 [%s - %s]:\n" +
                "X (Bắc) = %.3f m  |  Y (Đông) = %.3f m  (±%.1fm)\n" +
                "WGS-84: Lat = %.6f° N | Lon = %.6f° E%s",
            selectedProvince.provinceName,
            selectedProvince.dmsText,
            vnCoord.xNorth,
            vnCoord.yEast,
            location.accuracy,
            location.latitude,
            location.longitude,
            entityPart
        )
    }

    private fun handleSelectedDgnUri(uri: Uri) {
        val fileName = resolveFileName(uri) ?: "bando_diachinh.dgn"
        loadingJob?.cancel()
        loadingJob = lifecycleScope.launch {
            try {
                setLoadingState(true, 0, "Bắt đầu đọc tệp DGN: $fileName")
                val dgnData = gisFileLoader.loadDgnFile(
                    uri = uri,
                    displayName = fileName,
                    selectedProvince = selectedProvince,
                    selectedZoneWidth = selectedZoneWidth
                ) { progress, message ->
                    runOnUiThread {
                        setLoadingState(true, progress, message)
                    }
                }
                binding.mapCanvasView.setDgnData(dgnData)
                binding.switchLayerDgn.isChecked = true
                binding.tvLayerSummary.text = String.format(
                    Locale.US,
                    "DGN: %s (%d đối tượng, %d đỉnh, %d lớp Level)",
                    dgnData.fileName,
                    dgnData.entities.size,
                    dgnData.totalVertices,
                    dgnData.availableLevels.size
                )
                setLoadingState(false, 100, "Tải thành công tệp DGN: $fileName")
            } catch (e: Exception) {
                setLoadingState(false, 0, "Lỗi đọc DGN: ${e.localizedMessage}")
                Toast.makeText(
                    this@MainActivity,
                    "Không thể đọc tệp DGN: ${e.localizedMessage}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun handleSelectedGeoTiffUri(uri: Uri) {
        val fileName = resolveFileName(uri) ?: "anh_vientham.tif"
        loadingJob?.cancel()
        loadingJob = lifecycleScope.launch {
            try {
                setLoadingState(true, 0, "Bắt đầu giải mã ảnh GeoTIFF: $fileName")
                val dataset = gisFileLoader.loadGeoTiffFile(
                    uri = uri,
                    displayName = fileName,
                    selectedProvince = selectedProvince,
                    selectedZoneWidth = selectedZoneWidth
                ) { progress, message ->
                    runOnUiThread {
                        setLoadingState(true, progress, message)
                    }
                }
                binding.mapCanvasView.setGeoTiffData(dataset)
                binding.switchLayerTiff.isChecked = true
                binding.tvRasterSummary.text = String.format(
                    Locale.US,
                    "GeoTIFF: %s (%dx%d px | L0=%.2f°)",
                    dataset.fileName,
                    dataset.imageWidthPx,
                    dataset.imageHeightPx,
                    dataset.detectedMeridian
                )
                setLoadingState(false, 100, "Tải thành công ảnh GeoTIFF: $fileName")
            } catch (e: Exception) {
                setLoadingState(false, 0, "Lỗi đọc GeoTIFF: ${e.localizedMessage}")
                Toast.makeText(
                    this@MainActivity,
                    "Không thể đọc tệp GeoTIFF: ${e.localizedMessage}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun handleSelectedGoogleEarthPhotoUri(uri: Uri) {
        val fileName = resolveFileName(uri) ?: "google_earth_history.jpg"
        lifecycleScope.launch {
            try {
                setLoadingState(true, 30, "Đang nạp ảnh Google Earth để kéo tham chiếu: $fileName")
                val visibleBounds = binding.mapCanvasView.getCurrentVisibleWorldBounds()
                val photo = satelliteHistoryManager.loadCustomGoogleEarthImage(
                    uri = uri,
                    displayName = fileName,
                    yearLabel = binding.mapCanvasView.currentSatelliteEpoch.year.toString(),
                    targetViewportBounds = visibleBounds
                )
                binding.mapCanvasView.addCustomGoogleEarthPhoto(photo)
                binding.switchLayerSatellite.isChecked = true
                binding.switchDragCalibrate.isChecked = true
                setLoadingState(false, 100, "Đã nạp ảnh Google Earth ($fileName). Hãy vuốt/chụm để kéo tham chiếu!")
            } catch (e: Exception) {
                setLoadingState(false, 0, "Lỗi nạp ảnh Google Earth: ${e.localizedMessage}")
                Toast.makeText(
                    this@MainActivity,
                    "Không thể nạp ảnh Google Earth: ${e.localizedMessage}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun setLoadingState(isLoading: Boolean, progress: Int, statusText: String) {
        binding.progressContainer.visibility = if (isLoading) View.VISIBLE else View.GONE
        binding.progressBarLoading.progress = progress
        binding.tvProgressStatus.text = statusText
    }

    private fun updateProj4InfoBanner() {
        val proj4 = VN2000ProjectionHelper.buildProj4String(
            centralMeridian = selectedProvince.centralMeridianDeg,
            zoneWidth = selectedZoneWidth
        )
        binding.tvProj4Definition.text =
            "KTT L0 = ${selectedProvince.dmsText} (${selectedZoneWidth.degrees}° - k0=${selectedZoneWidth.scaleFactor}) | $proj4"
    }

    override fun onCoordinateTapped(
        vn2000Coord: VN2000ProjectionHelper.VN2000Coordinate,
        wgs84Coord: VN2000ProjectionHelper.Wgs84Coordinate,
        nearestEntityInfo: String?
    ) {
        val entitySuffix = if (nearestEntityInfo != null) "\nĐối tượng: $nearestEntityInfo" else ""
        binding.tvCoordinateOutput.text = String.format(
            Locale.US,
            "VN-2000 [%s - %s]:  X (Bắc) = %.3f m  |  Y (Đông) = %.3f m\n" +
                "WGS-84:  Lat = %.6f° N  |  Lon = %.6f° E%s",
            selectedProvince.provinceName,
            selectedProvince.dmsText,
            vn2000Coord.xNorth,
            vn2000Coord.yEast,
            wgs84Coord.latitude,
            wgs84Coord.longitude,
            entitySuffix
        )
    }

    override fun onViewportChanged(
        scaleMetersPerPixel: Double,
        visibleEntityCount: Int,
        shiftNorthMeters: Double,
        shiftEastMeters: Double
    ) {
        val runtime = Runtime.getRuntime()
        val usedRamMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
        binding.tvViewportStats.text = String.format(
            Locale.US,
            "%.2f m/px | DGN: %d | Năm %d (ΔX=%.1fm, ΔY=%.1fm) | RAM: %dMB",
            scaleMetersPerPixel,
            visibleEntityCount,
            binding.mapCanvasView.currentSatelliteEpoch.year,
            shiftNorthMeters,
            shiftEastMeters,
            usedRamMb
        )
    }

    private fun resolveFileName(uri: Uri): String? {
        var name: String? = null
        val cursor: Cursor? = contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val idx = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) {
                    name = it.getString(idx)
                }
            }
        }
        return name ?: uri.lastPathSegment
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isTrackingGps) {
            locationManager.removeUpdates(gpsLocationListener)
        }
    }
}