package ru.anisart.vv

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.*
import android.graphics.Color
import android.location.Location
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.preference.PreferenceManager
import android.support.v7.app.AppCompatActivity
import android.view.View
import android.widget.TextView
import android.widget.Toast
import butterknife.BindString
import butterknife.BindView
import butterknife.ButterKnife
import butterknife.OnClick
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.gson.Gson
import com.mapbox.mapboxsdk.annotations.PolygonOptions
import com.mapbox.mapboxsdk.annotations.PolylineOptions
import com.mapbox.mapboxsdk.camera.CameraPosition
import com.mapbox.mapboxsdk.camera.CameraUpdateFactory
import com.mapbox.mapboxsdk.constants.Style
import com.mapbox.mapboxsdk.geometry.LatLng
import com.mapbox.mapboxsdk.geometry.LatLngBounds
import com.mapbox.mapboxsdk.maps.MapView
import com.mapbox.mapboxsdk.maps.MapboxMap
import com.mapbox.mapboxsdk.style.functions.Function
import com.mapbox.mapboxsdk.style.functions.stops.Stop
import com.mapbox.mapboxsdk.style.functions.stops.Stops
import com.mapbox.mapboxsdk.style.layers.*
import com.mapbox.mapboxsdk.style.sources.GeoJsonSource
import com.mapbox.mapboxsdk.style.sources.RasterSource
import com.mapbox.mapboxsdk.style.sources.Source
import com.mapbox.mapboxsdk.style.sources.TileSet
import com.mapbox.services.commons.geojson.Feature
import com.mapbox.services.commons.geojson.FeatureCollection
import com.mapbox.services.commons.geojson.LineString
import com.mapbox.services.commons.geojson.Polygon
import io.reactivex.Observable
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.rxkotlin.toObservable
import io.reactivex.schedulers.Schedulers
import kotlinx.android.synthetic.main.activity_map.*
import org.jetbrains.anko.selector
import org.jetbrains.anko.toast
import permissions.dispatcher.NeedsPermission
import permissions.dispatcher.OnNeverAskAgain
import permissions.dispatcher.OnPermissionDenied
import permissions.dispatcher.RuntimePermissions
import java.util.*
import kotlin.collections.ArrayList
import kotlin.collections.HashSet

@RuntimePermissions
class MapActivity : AppCompatActivity(), SharedPreferences.OnSharedPreferenceChangeListener,
        ServiceConnection {

    private val CAMERA_ZOOM = 14.0
    private val BELOW_LAYER = "waterway-label"

    private val PLAY_SERVICES_RESOLUTION_REQUEST = 1
    private val SYNC_SETTINGS_REQUEST = 2

    private val EXPLORER_SOURCE_ID = "explorer_source"
    private val RIDES_SOURCE_ID = "rides_source"
    private val GRID_SOURCE_ID = "grid_source"
    private val TRACKING_LINE_SOURCE_ID = "tracking_line_source"
    private val TRACKING_TILES_SOURCE_ID = "tracking_tiles_source"
    private val HEATMAP_SOURCE_ID = "heatmap_source"
    private val EXPLORER_LAYER_ID = "explorer_layer"
    private val RIDES_LAYER_ID = "rides_layer"
    private val GRID_LAYER_ID = "grid_layer"
    private val TRACKING_LINE_LAYER_ID = "tracking_line_layer"
    private val TRACKING_TILES_LAYER_ID = "tracking_tiles_layer"
    private val HEATMAP_LAYER_ID = "heatmap_layer"
    private val CLUSTER_FLAG = "cluster"

    private val STATE_EXPLORER = "explorer"
    private val STATE_RIDES = "rides"
    private val STATE_GRID = "grid"
    private val STATE_HEATMAP = "heatmap"
    private val STATE_TARGET_POLYGON = "target_polygon"
//    private val STATE_ROUTE_POINT = "route_point"
    private val PREFERENCE_CAMERA_POSITION = "camera_position"

    @BindView(R.id.mapView)
    lateinit var mapView: MapView
    @BindView(R.id.debug)
    lateinit var debugView: TextView

    @BindString(R.string.key_color_explorer)
    lateinit var explorerKey: String
    @BindString(R.string.key_color_cluster)
    lateinit var clusterKey: String
    @BindString(R.string.key_color_rides)
    lateinit var ridesKey: String
    @BindString(R.string.key_color_grid)
    lateinit var gridKey: String
    @BindString(R.string.key_color_recorded_track)
    lateinit var recordedTrackKey: String
    @BindString(R.string.key_color_recorded_tiles)
    lateinit var recordedTilesKey: String
    @BindString(R.string.key_map_style)
    lateinit var mapKey: String
    @BindString(R.string.key_heatmap_type)
    lateinit var heatmapTypeKey: String
    @BindString(R.string.key_heatmap_style)
    lateinit var heatmapStyleKey: String

    @BindString(R.string.action_start)
    lateinit var startString: String
    @BindString(R.string.action_pause)
    lateinit var pauseString: String
    @BindString(R.string.action_resume)
    lateinit var resumeString: String
    @BindString(R.string.action_stop)
    lateinit var stopString: String
    @BindString(R.string.action_clear)
    lateinit var clearString: String
    @BindString(R.string.sync_settings)
    lateinit var syncSettingsString: String
    @BindString(R.string.style_settings)
    lateinit var styleSettingsString: String

    private lateinit var map: MapboxMap
    private lateinit var preferences: SharedPreferences
    private lateinit var settingsFragment: StyleSettingsFragment
    private lateinit var receiver: BroadcastReceiver
    private lateinit var firebaseAnalytics: FirebaseAnalytics

    private var explorer = false
    private var rides = false
    private var grid = false
    private var heatmap = false
    private var tagretPolygon: PolygonOptions? = null
    private var routeLine: PolylineOptions? = null
//    private var routePoint: LatLng? = null
    private var onMapInitObservable: Observable<Any>? = null
    private var service: TrackingService? = null
    private var mapAllowed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_map)
        ButterKnife.bind(this)
        supportActionBar?.hide()
        firebaseAnalytics = FirebaseAnalytics.getInstance(this)
        preferences = PreferenceManager.getDefaultSharedPreferences(this)
        settingsFragment = fragmentManager.findFragmentById(R.id.map_settings) as StyleSettingsFragment
        settingsFragment.setOnIconClickListener(object : StyleSettingsFragment.OnIconClickListener {
            override fun onIconClick() {
                showSettings(false)
            }
        })
        fragmentManager.beginTransaction().hide(settingsFragment).commit()

        savedInstanceState?.let {
            explorer = savedInstanceState.getBoolean(STATE_EXPLORER)
            rides = savedInstanceState.getBoolean(STATE_RIDES)
            grid = savedInstanceState.getBoolean(STATE_GRID)
            heatmap = savedInstanceState.getBoolean(STATE_HEATMAP)
            tagretPolygon = savedInstanceState.getParcelable(STATE_TARGET_POLYGON)
//            routePoint = savedInstanceState.getParcelable(STATE_ROUTE_POINT)
        }

        mapView.onCreate(savedInstanceState)
        val style = preferences.getString(mapKey, "")
        mapView.setStyleUrl(style)
        mapView.getMapAsync {
            mapAllowed = true
            map = it
            val positionString = preferences.getString(PREFERENCE_CAMERA_POSITION, null)
            positionString?.let {
                map.cameraPosition = Gson().fromJson<CameraPosition>(it)
            }
            initMap()
            onMapInitObservable?.subscribe()
//            routePoint?.let(this::route)

            mapView.addOnMapChangedListener {
                when (it) {
                    MapView.REGION_DID_CHANGE,
                    MapView.REGION_DID_CHANGE_ANIMATED,
                    MapView.DID_FINISH_LOADING_MAP -> {
                        if (BuildConfig.DEBUG) {
                            debugInfo()
                        }
                        if (grid) {
                            updateGrid()
                        }
                    }
                }
            }

            map.setOnMapClickListener {
                if (!settingsFragment.isHidden) {
                    showSettings(false)
                }
            }

//            map.setOnMapLongClickListener {
//                val actions = listOf("Alert", "Route")
//                selector("Lat %.3f Lon %.3f".format(Locale.US, it.latitude, it.longitude), actions,
//                        { _, i ->
//                            when (i) {
//                                0 -> alertTileWithPermissionCheck(it)
//                                1 -> route(it)
//                            }
//                        })
//            }

//            map.setOnInfoWindowClickListener {
//                routeLine?.polyline?.let(map::removePolyline)
//                map.removeMarker(it)
//                routeLine = null
//                routePoint = null
//                true
//            }
        }
        receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    TrackingService.ACTION_START ->
                        bindService(Intent(this@MapActivity, TrackingService::class.java), this@MapActivity, 0)
                    TrackingService.ACTION_TRACK ->
                        updateTracking(intent.getBooleanExtra(TrackingService.EXTRA_NEW_TILE, false))
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        System.err.println(intent.toString())
    }

    override fun onStart() {
        super.onStart()
        mapView.onStart()
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
        preferences.registerOnSharedPreferenceChangeListener(this)
        registerReceiver(receiver, intentFilter(TrackingService.ACTION_START, TrackingService.ACTION_TRACK))
        onMapInitObservable?.subscribe()
        if (onMapInitObservable == null) onMapInitObservable = onMapInitObservable ?: Observable.just(Any())
                    .doOnNext { bindService(Intent(this, TrackingService::class.java), this, 0) }
    }

    override fun onPause() {
        if (mapAllowed) {
            val positionString = map.cameraPosition.toJson()
            preferences.edit().putString(PREFERENCE_CAMERA_POSITION, positionString).apply()
        }
        tagretPolygon?.polygon?.let(map::removePolygon)
        tagretPolygon = null
        unbindService(this)
        unregisterReceiver(receiver)
        preferences.unregisterOnSharedPreferenceChangeListener(this)
        mapView.onPause()
        super.onPause()
    }

    override fun onStop() {
        mapView.onStop()
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_EXPLORER, explorer)
        outState.putBoolean(STATE_RIDES, rides)
        outState.putBoolean(STATE_GRID, grid)
        outState.putBoolean(STATE_HEATMAP, heatmap)
        outState.putParcelable(STATE_TARGET_POLYGON, tagretPolygon)
//        outState.putParcelable(STATE_ROUTE_POINT, routePoint)
        mapView.onSaveInstanceState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onLowMemory() {
        mapView.onLowMemory()
        super.onLowMemory()
    }

    override fun onDestroy() {
        mapView.onDestroy()
        super.onDestroy()
    }

    @SuppressLint("NeedOnRequestPermissionsResult")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        onRequestPermissionsResult(requestCode, grantResults)
    }

//    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
//        menuInflater.inflate(R.menu.menu_map, menu)
//        return true
//    }
//
//    override fun onOptionsItemSelected(item: MenuItem?): Boolean {
//        if (item?.itemId == R.id.action_colors) {
//            showSettings(settingsFragment.isHidden)
//            return true
//        }
//        return super.onOptionsItemSelected(item)
//    }

    override fun onBackPressed() {
        if (!settingsFragment.isHidden) {
            showSettings(false)
        } else {
            super.onBackPressed()
        }
    }

    override fun onSharedPreferenceChanged(preferences1: SharedPreferences?, key: String?) {
        when (key) {
            explorerKey,
            clusterKey -> updateExplorerLayerColors()
            ridesKey -> updateLayerColor(RIDES_LAYER_ID, key)
            gridKey -> updateLayerColor(GRID_LAYER_ID, key)
            recordedTrackKey -> updateLayerColor(TRACKING_LINE_LAYER_ID, key)
            recordedTilesKey -> updateLayerColor(TRACKING_TILES_LAYER_ID, key)
            mapKey -> {
                val style = preferences.getString(mapKey, Style.OUTDOORS)
                map.layers.forEach { map.removeLayer(it) }
                map.sources.forEach { map.removeSource(it) }
                map.setStyleUrl(style, { initMap() })
            }
            heatmapTypeKey,
            heatmapStyleKey -> setupHeatmap()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        when (requestCode) {
            PLAY_SERVICES_RESOLUTION_REQUEST ->
                toast(if (resultCode == Activity.RESULT_OK)
                    "Google Play Services have been enabled. Try again!"
                else
                    "Google Play Services has not been enabled. Tracking functionality is not available!")
            SYNC_SETTINGS_REQUEST -> if (resultCode == Activity.RESULT_OK) updateTilesAndRides()
            else -> super.onActivityResult(requestCode, resultCode, data)
        }
    }

    override fun onServiceConnected(componentName: ComponentName?, binder: IBinder?) {
        service = (binder as TrackingService.LocalBinder).getService()
//        service?.targetBounds?.let(this::drawTargetTile)
        updateTracking(true)
    }

    override fun onServiceDisconnected(componentName: ComponentName?) {
        service = null
        tagretPolygon?.polygon?.let(map::removePolygon)
        tagretPolygon = null
    }

    private fun initMap() {
        setupHeatmap()
        updateTilesAndRides()
        setupGrid()
        setupTracking()
    }

    private fun updateTilesAndRides() {
        listOf(EXPLORER_LAYER_ID, RIDES_LAYER_ID).forEach {
            map.removeLayer(it)
        }
        listOf(EXPLORER_SOURCE_ID, RIDES_SOURCE_ID).forEach {
            map.removeSource(it)
        }

        Observable
                .just(preferences)
                .map { it.getString(App.PREFERENCE_TILES, null) }
                .map { Gson().fromJson(it) ?: HashSet<Tile>() }
                .filter { it.isNotEmpty() }
                .flatMapIterable { it }
                .map {
                    val bbox = tile2bbox(it.x, it.y)
                    Feature.fromGeometry(Polygon.fromCoordinates(
                            arrayOf(
                                    arrayOf(
                                            doubleArrayOf(bbox.west, bbox.north),
                                            doubleArrayOf(bbox.east, bbox.north),
                                            doubleArrayOf(bbox.east, bbox.south),
                                            doubleArrayOf(bbox.west, bbox.south),
                                            doubleArrayOf(bbox.west, bbox.north)))))
                            .apply { addBooleanProperty(CLUSTER_FLAG, it.isCluster) }
                }
                .toList()
                .map(FeatureCollection::fromFeatures)
                .map { GeoJsonSource(EXPLORER_SOURCE_ID, it) }
                .subscribeOn(Schedulers.computation())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe({
                    setupExplorerTiles(it)
                }, {
                    it.printStackTrace()
                })

        Observable
                .just(preferences)
                .map { it.getString(App.PREFERENCE_RIDES_JSON, null) }
                .filter { it.isNotEmpty() }
                .map { GeoJsonSource(RIDES_SOURCE_ID, it) }
                .subscribeOn(Schedulers.computation())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe({
                    setupRides(it)
                }, {
                    it.printStackTrace()
                })
    }

    private fun setupExplorerTiles(source: Source) {
        map.addSource(source)
        map.addLayer(FillLayer(EXPLORER_LAYER_ID, EXPLORER_SOURCE_ID)
                .withProperties(
                        PropertyFactory.visibility(if (explorer) Property.VISIBLE else Property.NONE)
                ))
        updateExplorerLayerColors()
    }

    private fun setupRides(source: Source) {
        map.addSource(source)
        map.addLayerAbove(LineLayer(RIDES_LAYER_ID, RIDES_SOURCE_ID)
                .withProperties(
                        PropertyFactory.visibility(if (rides) Property.VISIBLE else Property.NONE)
                ), HEATMAP_LAYER_ID)
        updateLayerColor(RIDES_LAYER_ID, ridesKey)
    }

    private fun setupGrid() {
        map.addSource(GeoJsonSource(GRID_SOURCE_ID))
        val gridLayer = LineLayer(GRID_LAYER_ID, GRID_SOURCE_ID)
                .withProperties(
                        PropertyFactory.visibility(if (grid) Property.VISIBLE else Property.NONE)
                )
        gridLayer.minZoom = 10f
        map.addLayer(gridLayer)
        updateLayerColor(GRID_LAYER_ID, gridKey)
    }

    private fun setupTracking() {
        map.addSource(GeoJsonSource(TRACKING_LINE_SOURCE_ID))
        map.addLayer(LineLayer(TRACKING_LINE_LAYER_ID, TRACKING_LINE_SOURCE_ID))
        updateLayerColor(TRACKING_LINE_LAYER_ID, recordedTrackKey)
        map.addSource(GeoJsonSource(TRACKING_TILES_SOURCE_ID))
        map.addLayer(FillLayer(TRACKING_TILES_LAYER_ID, TRACKING_TILES_SOURCE_ID))
        updateLayerColor(TRACKING_TILES_LAYER_ID, recordedTilesKey)
    }

    private fun setupHeatmap() {
        map.removeLayer(HEATMAP_LAYER_ID)
        map.removeSource(HEATMAP_SOURCE_ID)
        val type = preferences.getString(heatmapTypeKey, "")
        val style = preferences.getString(heatmapStyleKey, "")
        map.addSource(RasterSource(HEATMAP_SOURCE_ID,
                TileSet("2.1.0", "http://heatmap-external-b.strava.com/tiles/$type/$style/{z}/{x}/{y}.png")
                        .apply { minZoom = 1f; maxZoom = 15f }, 256))
        map.addLayerBelow(RasterLayer(HEATMAP_LAYER_ID, HEATMAP_SOURCE_ID)
                .withProperties(PropertyFactory.visibility(
                        if (heatmap) Property.VISIBLE else Property.NONE
                )), BELOW_LAYER)
    }

    @OnClick(R.id.myLocationButton)
    fun onLocationButtonClick(v: View) {
        getLastLocationWithPermissionCheck()
    }

    @OnClick(R.id.explorerButton)
    fun onExplorerButtonClick(v: View) {
        val layer = map.getLayer(EXPLORER_LAYER_ID)
        if (layer != null) {
            explorer = !explorer
            layer.setProperties(PropertyFactory.visibility(
                    if (explorer) Property.VISIBLE else Property.NONE))
        } else {
            Toast.makeText(this, "No tiles!", Toast.LENGTH_SHORT).show()
        }
    }

    @OnClick(R.id.ridesButton)
    fun onRidesButtonClick(v: View) {
        val layer = map.getLayer(RIDES_LAYER_ID)
        if (layer != null) {
            rides = !rides
            layer.setProperties(PropertyFactory.visibility(
                    if (rides) Property.VISIBLE else Property.NONE))
        } else {
            Toast.makeText(this, "No rides!", Toast.LENGTH_SHORT).show()
        }
    }

    @OnClick(R.id.gridButton)
    fun onGridButtonClick(v: View) {
        val layer = map.getLayer(GRID_LAYER_ID)
        if (layer != null) {
            grid = !grid
            layer.setProperties(PropertyFactory.visibility(
                    if (grid) Property.VISIBLE else Property.NONE))
            if (grid) updateGrid()
        } else {
            Toast.makeText(this, "No grid!", Toast.LENGTH_SHORT).show()
        }
    }

    @OnClick(R.id.heatmapButton)
    fun onHeatmapButtonClick(v: View) {
        map.getLayer(HEATMAP_LAYER_ID)?.let {
            heatmap = !heatmap
            it.setProperties(PropertyFactory.visibility(
                    if (heatmap) Property.VISIBLE else Property.NONE))
        }
    }

    @OnClick(R.id.recordButton)
    fun onRecordButtonClick(v: View) {
        val buttons = when (service?.state) {
            TrackingService.State.RECORDING -> listOf(pauseString, stopString)
            TrackingService.State.PAUSED -> listOf(resumeString, stopString)
            else -> listOf(startString, clearString)
        }

        selector(getString(R.string.title_record_dialog), buttons, { _, i ->
            when (buttons[i]) {
                startString -> startRecordingWithPermissionCheck()
                pauseString -> pauseRecording()
                resumeString -> resumeRecording()
                stopString -> stopRecording()
                clearString -> clearTracking()
            }
        })
    }

    @OnClick(R.id.settingsButton)
    fun onSettingsButtonClick(v: View) {
        val buttons = listOf(syncSettingsString, styleSettingsString)
        selector(null, buttons, { _, i ->
            when (buttons[i]) {
                syncSettingsString -> startActivityForResult(Intent(this, MainActivity::class.java), SYNC_SETTINGS_REQUEST)
                styleSettingsString -> showSettings(settingsFragment.isHidden)
            }
        })
    }

    private fun setCameraPosition(location: Location) {
        map.animateCamera(CameraUpdateFactory.newLatLngZoom(
                LatLng(location.latitude, location.longitude), CAMERA_ZOOM))
    }

    @NeedsPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    fun getLastLocation() {
        if (map.myLocation != null) {
            setCameraPosition(map.myLocation as Location)
        } else {
            map.setOnMyLocationChangeListener(null)
            map.isMyLocationEnabled = true
            map.setOnMyLocationChangeListener { location ->
                run {
                    setCameraPosition(location as Location)
                    map.setOnMyLocationChangeListener(null)
                }
            }

        }
    }

    @OnPermissionDenied(Manifest.permission.ACCESS_FINE_LOCATION)
    fun onLocationPermissionDenied() {
        Toast.makeText(this, "Permission is required to show your location!", Toast.LENGTH_SHORT).show()
    }

    @OnNeverAskAgain(Manifest.permission.ACCESS_FINE_LOCATION)
    fun onLocationNeverAskAgain() {
        Toast.makeText(this, "Check permissions for app in System Settings!", Toast.LENGTH_SHORT).show()
    }

    private fun debugInfo() {
        debugView.text = "z = %.2f, lat = %.2f, lon = %.2f".format(
                Locale.US,
                map.cameraPosition.zoom,
                map.cameraPosition.target.latitude,
                map.cameraPosition.target.longitude)
    }

    private fun updateGrid() {
        map.getLayer(GRID_LAYER_ID) ?: return
        if (map.cameraPosition.zoom < 9.9) return

        val bounds = map.projection.visibleRegion.latLngBounds
        val x0 = lon2tile(bounds.lonWest)
        val x1 = lon2tile(bounds.lonEast)
        val y0 = lat2tile(bounds.latNorth)
        val y1 = lat2tile(bounds.latSouth)
        val gridLines = ArrayList<Feature>()
        for (x in x0 - 1..x1 + 1) {
            for (y in y0 - 1..y1 + 1) {
                val bbox = tile2bbox(x, y)
                val feature = Feature.fromGeometry(LineString.fromCoordinates(
                        arrayOf(
                                doubleArrayOf(bbox.west, bbox.south),
                                doubleArrayOf(bbox.west, bbox.north),
                                doubleArrayOf(bbox.east, bbox.north)
                        )))
                gridLines.add(feature)
            }
        }
        val gridCollection = FeatureCollection.fromFeatures(gridLines)
        (map.getSource(GRID_SOURCE_ID) as GeoJsonSource).setGeoJson(gridCollection)
    }

    private fun updateTracking(newTile: Boolean) {
        if (service == null) return

        val lineSource = map.getSourceAs<GeoJsonSource>(TRACKING_LINE_SOURCE_ID)
        lineSource?.setGeoJson(LineString.fromCoordinates(
                service!!.track.map {
                    doubleArrayOf(it.longitude, it.latitude)
                }.toTypedArray()))
        if (newTile) {
            service!!.acquiredTiles.toObservable()
                    .map {
                        val bbox = tile2bbox(it.x, it.y)
                        Feature.fromGeometry(Polygon.fromCoordinates(
                                arrayOf(
                                        arrayOf(
                                                doubleArrayOf(bbox.west, bbox.north),
                                                doubleArrayOf(bbox.east, bbox.north),
                                                doubleArrayOf(bbox.east, bbox.south),
                                                doubleArrayOf(bbox.west, bbox.south),
                                                doubleArrayOf(bbox.west, bbox.north)))))
                    }
                    .toList()
                    .map(FeatureCollection::fromFeatures)
                    .map {
                        val tileSource = map.getSourceAs<GeoJsonSource>(TRACKING_TILES_SOURCE_ID)
                        tileSource!!.setGeoJson(it)
                    }
                    .subscribeOn(Schedulers.computation())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe({}, {
                        it.printStackTrace()
                    })
        }
    }

    private fun clearTracking() {
        map.getSourceAs<GeoJsonSource>(TRACKING_LINE_SOURCE_ID)?.setGeoJson(FeatureCollection.fromFeatures(ArrayList()))
        map.getSourceAs<GeoJsonSource>(TRACKING_TILES_SOURCE_ID)?.setGeoJson(FeatureCollection.fromFeatures(ArrayList()))
    }

    private fun showSettings(isShow: Boolean) {
        val transaction = fragmentManager.beginTransaction()
        transaction.setCustomAnimations(R.animator.enter_from_left, R.animator.exit_to_left)
        if (isShow) {
            settingsButton.hide()
            recordButton.hide()
            transaction.show(settingsFragment)
        } else {
            transaction.hide(settingsFragment)
            settingsButton.show()
            recordButton.show()
        }
        transaction.commit()
    }

//    private fun addDestinationMarker(point: LatLng) {
//        map.markers.forEach(map::removeMarker)
//        map.addMarker(MarkerOptions()
//                .position(LatLng(point.latitude, point.longitude))
//                .title("Destination")
//                .snippet("Click to remove"))
//    }

//    @NeedsPermission(Manifest.permission.ACCESS_FINE_LOCATION)
//    fun alertTile(point: LatLng) {
//        if (service != null) unbindService(this)
//        if (!checkPlayServices()) return
//
//        val bounds = point2bounds(point.latitude, point.longitude)
//
//    }

    @NeedsPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    fun startRecording() {
        if (!checkPlayServices()) return

        val serviceIntent = Intent(this, TrackingService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    private fun pauseRecording() {
        sendBroadcast(Intent(TrackingService.ACTION_PAUSE))
    }

    private fun resumeRecording() {
        sendBroadcast(Intent(TrackingService.ACTION_RESUME))
    }

    private fun stopRecording() {
        sendBroadcast(Intent(TrackingService.ACTION_STOP))
    }

    private fun drawTargetTile(bounds: LatLngBounds) {
        tagretPolygon?.polygon?.let(map::removePolygon)
        tagretPolygon = PolygonOptions()
                .add(bounds.northWest)
                .add(bounds.northEast)
                .add(bounds.southEast)
                .add(bounds.southWest)
                .add(bounds.northWest)
                .fillColor(Color.MAGENTA)
                .alpha(0.3f)
        tagretPolygon?.let(map::addPolygon)
    }

//    private fun route(point: LatLng) {
//        routeLine?.polyline?.let(map::removePolyline)
//        addDestinationMarker(point)
//        val myLocation = map.myLocation ?: return
//        val origin = Position.fromLngLat(myLocation.longitude, myLocation.latitude)
//        val destination = Position.fromLngLat(point.longitude, point.latitude)
//        MapboxDirectionsRx.Builder()
//                .setOrigin(origin)
//                .setDestination(destination)
//                .setOverview(DirectionsCriteria.OVERVIEW_FULL)
//                .setProfile(DirectionsCriteria.PROFILE_CYCLING)
//                .setAccessToken(Mapbox.getAccessToken())
//                .build()
//                .observable
//                .subscribeOn(Schedulers.io())
//                .observeOn(AndroidSchedulers.mainThread())
//                .subscribe({
//                    drawRoute(it.routes.first())
//                    routePoint = point
//                }, {
//                    map.markers.forEach(map::removeMarker)
//                    it.printStackTrace()
//                    toast("Route not found.")
//                })
//    }
//
//    private fun drawRoute(route: DirectionsRoute) {
//        val lineString = LineString.fromPolyline(route.geometry, PRECISION_6)
//        val points = arrayListOf<LatLng>()
//        lineString.coordinates.forEach { points.add(LatLng(
//                it.latitude,
//                it.longitude)) }
//        routeLine = PolylineOptions()
//                .addAll(points)
//                .color(Color.parseColor("#009688"))
//                .width(5f)
//        routeLine?.let(map::addPolyline)
//    }

    private fun updateExplorerLayerColors() {
        val layer = map.getLayer(EXPLORER_LAYER_ID) ?: return
        val colorE = preferences.getInt(explorerKey, 0)
        val alphaE = alfaFromColor(colorE)
        val colorC = preferences.getInt(clusterKey, 0)
        val alphaC = alfaFromColor(colorC)
        layer.setProperties(
                PropertyFactory.fillOutlineColor(
                        Function.property(
                                CLUSTER_FLAG,
                                Stops.categorical(Stop.stop(true, PropertyFactory.fillOutlineColor(colorC))))
                                .withDefaultValue(PropertyFactory.fillOutlineColor(colorE))),
                PropertyFactory.fillColor(
                        Function.property(
                                CLUSTER_FLAG,
                                Stops.categorical(Stop.stop(true, PropertyFactory.fillColor(colorC))))
                                .withDefaultValue(PropertyFactory.fillColor(colorE))),
                PropertyFactory.fillOpacity(
                        Function.property(
                                CLUSTER_FLAG,
                                Stops.categorical(Stop.stop(true, PropertyFactory.fillOpacity(alphaC))))
                                .withDefaultValue(PropertyFactory.fillOpacity(alphaE))))
    }

    private fun updateLayerColor(layerId: String, preferenceKey: String) {
        val layer = map.getLayer(layerId) ?: return
        val color = preferences.getInt(preferenceKey, 0)
        val alpha = alfaFromColor(color)
        when (layer) {
            is LineLayer -> layer.setProperties(
                    PropertyFactory.lineColor(color),
                    PropertyFactory.lineOpacity(alpha))
            is FillLayer -> layer.setProperties(
                    PropertyFactory.fillColor(color),
                    PropertyFactory.fillOpacity(alpha))
        }

    }

    private fun checkPlayServices(): Boolean {
        val googleAPI = GoogleApiAvailability.getInstance()
        val result = googleAPI.isGooglePlayServicesAvailable(this)
        if (result != ConnectionResult.SUCCESS)
        {
            if (googleAPI.isUserResolvableError(result))
            {
                googleAPI.getErrorDialog(this, result,
                        PLAY_SERVICES_RESOLUTION_REQUEST).show()
            }
            return false
        }
        return true
    }
}
