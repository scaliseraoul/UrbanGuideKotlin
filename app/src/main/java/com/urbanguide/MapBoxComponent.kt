package com.urbanguide

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.gms.maps.model.LatLng
import com.mapbox.common.Cancelable
import com.mapbox.common.MapboxOptions
import com.mapbox.geojson.Point
import com.mapbox.maps.CameraOptions
import com.mapbox.maps.MapInitOptions
import com.mapbox.maps.MapView
import com.mapbox.maps.Style
import com.mapbox.maps.extension.style.layers.generated.rasterLayer
import com.mapbox.maps.extension.style.sources.generated.rasterSource
import com.mapbox.maps.extension.style.style
import com.mapbox.maps.plugin.annotation.annotations
import com.mapbox.maps.plugin.annotation.generated.PointAnnotationManager
import com.mapbox.maps.plugin.annotation.generated.PointAnnotationOptions
import com.mapbox.maps.plugin.annotation.generated.createPointAnnotationManager
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.receiveAsFlow

@Composable
fun MapBoxComponent(mapData: List<DataBeam>, mqttEventSharedFlow: MutableSharedFlow<MqttEvent>, mqttManager: MQTTManager) {
    val BaseTopic = "AndroidKotlinMapbox"
    val context = LocalContext.current
    val modena = convertLatLangToPoint(LatLng(44.646469, 10.925139))

    // Use remember to instantiate and remember the initial state
    var markers by remember { mutableStateOf(listOf<MarkerData>()) }
    var heatmaps by remember { mutableStateOf(listOf<HeatmapData>()) }

    // Update markers and heatmaps based on mapData
    markers = mapData.filterIsInstance<MarkerData>()
    heatmaps = mapData.filterIsInstance<HeatmapData>()

    // Prepare your API key and tile server URL
    val apiKey = BuildConfig.MAPS_API_KEY
    val tileServerUrl = "https://airquality.googleapis.com/v1/mapTypes/UAQI_RED_GREEN/heatmapTiles/{z}/{x}/{y}?key=${apiKey}"

    // MapView initialization
    val mapView = rememberMapBoxViewWithLifecycle()
    val pointAnnotationManager = remember { mapView.annotations.createPointAnnotationManager() }

    val mqttEvents = mqttEventSharedFlow.asSharedFlow()


    mqttManager.subscribe("$BaseTopic${Topics.DrawPoint}Receive")
    mqttManager.subscribe("$BaseTopic${Topics.DrawPointBatch}Receive")
    mqttManager.subscribe("$BaseTopic${Topics.MoveMap}Receive")


    // Observe heatmaps list and update the map style accordingly
    LaunchedEffect(heatmaps) {
        mapView.mapboxMap.loadStyle(
            style(Style.STANDARD) {
                if (heatmaps.isNotEmpty()) {
                    +rasterSource("xyz-tile-source") {
                        tiles(listOf(tileServerUrl))
                        tileSize(256)
                        maxzoom(16)
                    }
                    +rasterLayer("xyz-tile-layer", "xyz-tile-source") {
                        rasterOpacity(0.8)
                    }
                }
            }
        ) {
            // Apply camera settings after style is loaded
            mapView.mapboxMap.setCamera(
                CameraOptions.Builder()
                    .center(modena)
                    .zoom(15.0)
                    .pitch(30.0)
                    .build()
            )

        }
    }

    LaunchedEffect(markers) {
        addMarkersToMap(context, markers, pointAnnotationManager)
    }


    LaunchedEffect(key1 = mqttEvents) {

        var idleCancelable: Cancelable? = null
        mqttEvents.collect { mqttEvent ->
            when (mqttEvent) {
                is MqttEvent.DrawPointEvent -> {
                    val startTime = System.nanoTime()
                    val point = convertLatLangToPoint(mqttEvent.position)
                    val iconImage = BitmapFactory.decodeResource(context.resources, R.drawable.mapbox_marker_icon_20px_blue)

                    val pointAnnotationOptions = PointAnnotationOptions()
                        .withPoint(point)
                        .withIconImage(iconImage)
                        .withTextField(mqttEvent.title)

                    pointAnnotationManager.create(pointAnnotationOptions)
                    val elapsedTime = System.nanoTime() - startTime
                    val mqttPayload = "${mqttEvent.timestamp_sent},Android,Kotlin,MapBox,${Topics.DrawPoint},0,0,$elapsedTime"
                    mqttManager.publish("$BaseTopic${Topics.DrawPoint}Complete",mqttPayload)
                    Log.d("Performance", "payload: $mqttPayload")
                }

                is MqttEvent.DrawPointEventBatch -> {
                    val startTime = System.nanoTime()

                    val eventlist = mqttEvent.events

                    eventlist.forEach { event ->
                        val point = convertLatLangToPoint(event.position)
                        val iconImage = BitmapFactory.decodeResource(context.resources, R.drawable.mapbox_marker_icon_20px_blue)

                        val pointAnnotationOptions = PointAnnotationOptions()
                            .withPoint(point)
                            .withIconImage(iconImage)
                            .withTextField(event.title)

                        pointAnnotationManager.create(pointAnnotationOptions)
                    }

                    val elapsedTime = System.nanoTime() - startTime
                    val mqttPayload = "${mqttEvent.timestamp_sent},Android,Kotlin,MapBox,${Topics.DrawPointBatch},0,0,$elapsedTime"
                    mqttManager.publish("$BaseTopic${Topics.DrawPointBatch}Complete",mqttPayload)
                    Log.d("Performance", "payload: $mqttPayload")
                }

                is MqttEvent.MoveMapEvent -> {
                    idleCancelable?.cancel()

                    var startTime : Long = 0
                    var elapsedTime : Long

                    idleCancelable = mapView.mapboxMap.subscribeCameraChanged {
                        elapsedTime = System.nanoTime() - startTime
                        val mqttPayload =
                            "${mqttEvent.timestamp_sent},Android,Kotlin,MapBox,${Topics.MoveMap},0,0,$elapsedTime"
                        mqttManager.publish("$BaseTopic${Topics.MoveMap}Complete", mqttPayload)
                        Log.d("Performance", "event sent: $mqttPayload")
                    }

                    startTime = System.nanoTime()
                    mapView.mapboxMap.setCamera(
                        CameraOptions.Builder()
                            .center(convertLatLangToPoint(mqttEvent.position))
                            .zoom(15.0)
                            .pitch(30.0)
                            .build()
                    )
                }

                else -> {}
            }
        }
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { mapView },
    )
}

@Composable
fun rememberMapBoxViewWithLifecycle(): MapView {
    val context = LocalContext.current
    val mapView = remember {
        MapView(context, MapInitOptions(context).apply {
            MapboxOptions.accessToken = BuildConfig.MAPBOX_PUBLIC_KEY
        })
    }

    // Handle lifecycle events
    DisposableEffect(context) {
        onDispose {
            mapView.onDestroy()
        }
    }

    return mapView
}

fun convertLatLangToPoint(latLang : LatLng) : Point {
    return Point.fromLngLat(latLang.longitude,latLang.latitude)
}

fun addMarkerToMap(context: Context, marker: MarkerData, pointAnnotationManager: PointAnnotationManager?) {

    // Add new markers
    val point = convertLatLangToPoint(marker.position)
    val iconImage = BitmapFactory.decodeResource(context.resources, R.drawable.mapbox_marker_icon_20px_blue)
    //start drawing fun
    val pointAnnotationOptions = PointAnnotationOptions()
        .withPoint(point)
        .withIconImage(iconImage)
        .withTextField(marker.title)

    pointAnnotationManager?.create(pointAnnotationOptions)
}

fun addMarkersToMap(context: Context, markers: List<MarkerData>, pointAnnotationManager: PointAnnotationManager?) {
    pointAnnotationManager?.deleteAll()
    // Add new markers
    markers.forEach { markerData ->
        addMarkerToMap(context,markerData,pointAnnotationManager)
    }
}
