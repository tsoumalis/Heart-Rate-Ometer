@file:Suppress("DEPRECATION")

package net.kibotu.heartrateometer

import android.content.Context
import android.graphics.Point
import android.hardware.Camera
import android.os.Build
import android.os.Handler
import android.os.PowerManager
import android.util.DisplayMetrics
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import de.charite.balsam.utils.camera.CameraModule
import de.charite.balsam.utils.camera.CameraSupport
import io.reactivex.Observable
import io.reactivex.subjects.PublishSubject
import org.apache.commons.collections4.queue.CircularFifoQueue
import java.lang.ref.WeakReference
import java.util.*
import java.util.Arrays.asList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.collections.ArrayList
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.math.sqrt


/**
 * Created by <a href="https://about.me/janrabe">Jan Rabe</a>.
 */
open class HeartRateOmeter {

    private val TAG: String = javaClass.simpleName

    companion object {
        var enableLogging: Boolean = false
    }

    enum class PulseType { OFF, ON }

    data class Bpm(val value: Int, val type: PulseType)

    private val MIN_RED_AVG_VALUE = 120
    private val OTHER_COLOR_MAX_VALUE = 90
    private val FINGER_DEBOUNCE = 2000

    private var wakeLockTimeOut: Long = 10_000

    private var surfaceHolder: SurfaceHolder? = null

    private var wakelock: PowerManager.WakeLock? = null

    private lateinit var previewCallback: Camera.PreviewCallback

    private lateinit var surfaceCallback: SurfaceHolder.Callback

    protected val publishSubject: PublishSubject<Bpm>

    private var context: WeakReference<Context>? = null

    private var cameraSupport: CameraSupport? = null

    private var powerManager: PowerManager? = null
        get() = context?.get()?.getSystemService(Context.POWER_SERVICE) as? PowerManager?

    private var fingerDetectionListener: ((Boolean) -> Unit)? = null
    private var chartDataListener: ((Int) -> Unit)? = null
    private var rawDataListener: ((Long, Float, Float, Float) -> Unit)? = null

    init {
        publishSubject = PublishSubject.create<Bpm>()
    }

    var averageTimer: Int = -1

    fun withAverageAfterSeconds(averageTimer: Int): HeartRateOmeter {
        this.averageTimer = averageTimer
        return this
    }

    fun bpmUpdates(surfaceView: SurfaceView): Observable<Bpm> {
        return bpmUpdates(surfaceView.context, surfaceView.holder)
    }

    private fun bpmUpdates(context: Context, surfaceHolder: SurfaceHolder): Observable<Bpm> {

        previewCallback = if (averageTimer == -1)
            createCameraPreviewCallback()
        else
            createCameraPreviewCallback2()

        surfaceCallback = createSurfaceHolderCallback()

        this.context = WeakReference(context)
        this.surfaceHolder = surfaceHolder
        return publishSubject
                .doOnSubscribe {
                    publishSubject.onNext(Bpm(-1, PulseType.OFF))
                    start()
                }
                .doOnDispose { cleanUp() }
    }

    private fun start() {
        log("start")

        wakelock = powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, context?.get()?.javaClass?.canonicalName)
        wakelock?.acquire(wakeLockTimeOut)

        context?.get()?.let {
            cameraSupport = CameraModule.provideCameraSupport(context = it).open(0)
        }

        // portrait
        cameraSupport?.setDisplayOrientation(90)
        log(cameraSupport?.getOrientation(0).toString())

        surfaceHolder?.addCallback(surfaceCallback)
        surfaceHolder?.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS)

        addCallbacks()

        startPreview()
    }

    private fun addCallbacks() {
        try {
            cameraSupport?.setPreviewDisplay(surfaceHolder!!)
            cameraSupport?.setPreviewCallback(previewCallback)
        } catch (throwable: Throwable) {
            if (enableLogging)
                throwable.printStackTrace()
        }
    }

    data class Dimension(val width: Int, val height: Int)

    private fun getScreenDimensions(): Dimension {

        val dm = DisplayMetrics()
        val display: android.view.Display = (context?.get()?.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay
        display.getMetrics(dm)

        var screenWidth = dm.widthPixels
        var screenHeight = dm.heightPixels

        if (Build.VERSION.SDK_INT in 14..16) {
            try {
                screenWidth = android.view.Display::class.java.getMethod("getRawWidth").invoke(display) as Int
                screenHeight = android.view.Display::class.java.getMethod("getRawHeight").invoke(display) as Int
            } catch (ignored: Exception) {
            }

        }
        if (Build.VERSION.SDK_INT >= 17) {
            try {
                val realSize = Point()
                android.view.Display::class.java.getMethod("getRealSize", Point::class.java).invoke(display, realSize)
                screenWidth = realSize.x
                screenHeight = realSize.y
            } catch (ignored: Exception) {
            }

        }

        return Dimension(screenWidth, screenHeight)
    }

    private fun getScreenDimensionsLandscape(): Dimension {
        val (width, height) = getScreenDimensions()
        return Dimension(max(width, height), min(width, height))
    }

    private fun startPreview() {
        val screenDimensionsLandscape = getScreenDimensionsLandscape()
        setCameraParameter(screenDimensionsLandscape.width, screenDimensionsLandscape.height)
        cameraSupport?.startPreview()
    }

    private fun setCameraParameter(width: Int, height: Int) {

        val parameters = cameraSupport?.parameters
        parameters?.flashMode = Camera.Parameters.FLASH_MODE_TORCH

        if (parameters?.maxExposureCompensation != parameters?.minExposureCompensation) {
            //  parameters?.exposureCompensation = 0
        }
        if (parameters?.isAutoExposureLockSupported == true) {
            // parameters.autoExposureLock = true
        }
        if (parameters?.isAutoWhiteBalanceLockSupported == true) {
            // parameters.autoWhiteBalanceLock = true
        }


        // parameters?.setPreviewSize(width, height)
        getSmallestPreviewSize(width, height, parameters)?.let {
            parameters?.setPreviewSize(it.width, it.height)
            log("Using width ${it.width} and height ${it.height}")
        }

        cameraSupport?.parameters = parameters
    }

    private fun createSurfaceHolderCallback(): SurfaceHolder.Callback {
        return object : SurfaceHolder.Callback {

            override fun surfaceCreated(holder: SurfaceHolder) {
                addCallbacks()
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                startPreview()
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {}
        }
    }

    private var fingerDetected: Boolean = false
        set(value) {
            if (field != value)
                fingerDetectionListener?.invoke(value)
            field = value
        }

    private fun createCameraPreviewCallback(): Camera.PreviewCallback {
        return object : Camera.PreviewCallback {

            val PROCESSING = AtomicBoolean(false)
            val sampleSize = 128
            var counter = 0
            var bpm: Int = -1

            val fft = FFT(sampleSize)

            val sampleQueue = CircularFifoQueue<Double>(sampleSize)
            val redQueue = CircularFifoQueue<Double>(sampleSize)
            val greenQueue = CircularFifoQueue<Double>(sampleSize)
            val timeQueue = CircularFifoQueue<Long>(sampleSize)
            val bpmQueue = CircularFifoQueue<Int>(150)
            val bpmArrayList = ArrayList<Int>()
            val powerList = ArrayList<Double>()

            var waitingToChangeFingerState = false

            override fun onPreviewFrame(data: ByteArray?, camera: Camera?) {

                if (data == null) {
                    log("Data is null!")
                    return
                }

                if (camera == null) {
                    log("Camera is null!")
                    return
                }

                val size = camera.parameters.previewSize
                if (size == null) {
                    log("Size is null!")
                    return
                }

                if (!PROCESSING.compareAndSet(false, true)) {
                    return
                }

                val width = size.width
                val height = size.height

                val rgbh = MathHelper.decodeYUV420SPtoRGBHAverage(data.clone(), width, height)
                val imgAvg = (rgbh[0] + rgbh[1]).toInt()

                chartDataListener?.invoke(imgAvg)
                if (fingerNotValid(rgbh)) {
                    PROCESSING.set(false)
                    fingerDetected = false
                    return
                }

                if (waitingToChangeFingerState) {
                    PROCESSING.set(false)
                    return
                }

                if (!fingerDetected) {
                    waitingToChangeFingerState = true
                    Handler().postDelayed({
                        if (!fingerNotValid(rgbh))
                            fingerDetected = true
                        waitingToChangeFingerState = false
                    }, FINGER_DEBOUNCE.toLong())
                    PROCESSING.set(false)
                    return
                }

                fingerDetected = true

                rawDataListener?.invoke(System.currentTimeMillis(), rgbh[0], rgbh[1], rgbh[2])

                sampleQueue.add(imgAvg.toDouble())
                redQueue.add(rgbh[0].toDouble())
                greenQueue.add(rgbh[1].toDouble())
                timeQueue.add(System.currentTimeMillis())

                val y = DoubleArray(sampleSize)
                val x = toPrimitive(sampleQueue.toArray(arrayOfNulls<Double>(0)) as Array<Double>)
                val time = toPrimitive(timeQueue.toArray(arrayOfNulls<Long>(0)) as Array<Long>)

                if (timeQueue.size < sampleSize) {
                    PROCESSING.set(false)

                    return
                }

                val Fs = timeQueue.size.toDouble() / (time!![timeQueue.size - 1] - time[0]).toDouble() * 1000

                fft.fft(x!!, y)

                val low = ((sampleSize * 50).toDouble() / 60.0 / Fs).toFloat().roundToLong()
                val high = ((sampleSize * 160).toDouble() / 60.0 / Fs).toFloat().roundToLong()

                var bestI = 0
                var bestV = 0.0
                for (i in low.toInt() until high.toInt()) {
                    val value = sqrt(x[i] * x[i] + y[i] * y[i])

                    if (value > bestV) {
                        bestV = value
                        bestI = i
                    }
                }
                powerList.add(bestV)

                if (bestV < 30)
                {
                    PROCESSING.set(false)
                    return
                }

                bpm = (bestI.toDouble() * Fs * 60.0 / sampleSize).toFloat().roundToLong().toInt()
                bpmQueue.add(bpm)
                bpmArrayList.add(bpm)

                var sum = 0
                for (i in bpmQueue){
                    sum += i
                }

                val average = sum / bpmQueue.size

                log("bpm=$bpm")

                publishSubject.onNext(Bpm(average, PulseType.ON))

                counter++

                PROCESSING.set(false)
            }
        }
    }

    fun fingerNotValid(rgbh: FloatArray): Boolean {
        val imageRedAvg = rgbh[0]
        val imageGreenAvg = rgbh[1]
        val imageBlueAvg = rgbh[2]
        return (imageRedAvg < MIN_RED_AVG_VALUE || imageGreenAvg > imageRedAvg ||
                imageBlueAvg > imageRedAvg ||
                imageBlueAvg > OTHER_COLOR_MAX_VALUE ||
                imageGreenAvg > OTHER_COLOR_MAX_VALUE)
    }

    private fun createCameraPreviewCallback2(): Camera.PreviewCallback {

        return object : Camera.PreviewCallback {

            var beatsIndex = 0
            var beats = 0.0
            var startTime = System.currentTimeMillis()
            var averageIndex = 0

            val PROCESSING = AtomicBoolean(false)

            val AVERAGE_ARRAY_SIZE = 6
            val AVERAGE_ARRAY = IntArray(AVERAGE_ARRAY_SIZE)

            val BEATS_ARRAY_SIZE = 3
            val BEATS_ARRAY = IntArray(BEATS_ARRAY_SIZE)

            var currentPixelType: PulseType = PulseType.OFF
            var fingerDetectedTimestamp = 0
            var waitingToChangeFingerState = false

            private var previousBeatsAverage: Int = 0

            override fun onPreviewFrame(data: ByteArray?, camera: Camera) {

                if (data == null) {
                    log("Data is null!")
                    return
                }

                val size = camera.parameters.previewSize
                if (size == null) {
                    log("Size is null!")
                    return
                }

                if (!PROCESSING.compareAndSet(false, true)) {
                    log("Have to return...")
                    return
                }

                val width = size.width
                val height = size.height

                // Logger.d("SIZE: width: " + width + ", height: " + height);

                val rgbh = MathHelper.decodeYUV420SPtoRGBHAverage(data.clone(), width, height)
                val imageAverage = (rgbh[0].toInt() + rgbh[1].toInt()) / 2

                if (fingerNotValid(rgbh)) {
                    PROCESSING.set(false)
                    fingerDetected = false
                    return
                }

                if (waitingToChangeFingerState) {
                    PROCESSING.set(false)
                    return
                }
                if (!fingerDetected) {
                    waitingToChangeFingerState = true
                    Handler().postDelayed({
                        if (!fingerNotValid(rgbh))
                            fingerDetected = true
                        waitingToChangeFingerState = false
                    }, FINGER_DEBOUNCE.toLong())
                    PROCESSING.set(false)
                    return
                }
                chartDataListener?.invoke(imageAverage)
                rawDataListener?.invoke(System.currentTimeMillis(), rgbh[0], rgbh[1], rgbh[2])

                log("imageAverage: $imageAverage")

                var averageArrayAverage = 0
                var averageArrayCount = 0

                for (averageEntry in AVERAGE_ARRAY) {
                    if (averageEntry > 0) {
                        averageArrayAverage += averageEntry
                        averageArrayCount++
                    }
                }

                val rollingAverage = if (averageArrayCount > 0) averageArrayAverage / averageArrayCount else 0

                log("rollingAverage: $rollingAverage")

                var newType = currentPixelType

                if (imageAverage < rollingAverage) {
                    newType = PulseType.ON
                    if (newType != currentPixelType) {
                        beats++
                    }
                } else if (imageAverage > rollingAverage) {
                    newType = PulseType.OFF
                }

                if (averageIndex == AVERAGE_ARRAY_SIZE) {
                    averageIndex = 0
                }

                AVERAGE_ARRAY[averageIndex] = imageAverage
                averageIndex++

                if (newType != currentPixelType) {
                    currentPixelType = newType
                    publishSubject.onNext(Bpm(previousBeatsAverage, currentPixelType))
                }

                val endTime = System.currentTimeMillis()
                val totalTimeInSecs = (endTime - startTime) / 1000.0
                log("totalTimeInSecs: $totalTimeInSecs >= averageTimer: $averageTimer")
                if (totalTimeInSecs >= averageTimer) {
                    val beatsPerSecond = beats / totalTimeInSecs
                    val beatsPerMinute = (beatsPerSecond * 60.0).toInt()
                    if (beatsPerMinute < 40 || beatsPerMinute > 180) {
                        startTime = System.currentTimeMillis()
                        beats = 0.0
                        PROCESSING.set(false)
                        return
                    }

                    if (beatsIndex == BEATS_ARRAY_SIZE) {
                        beatsIndex = 0
                    }

                    BEATS_ARRAY[beatsIndex] = beatsPerMinute
                    beatsIndex++

                    var beatsArrayAverage = 0
                    var beatsArrayCount = 0

                    for (beatsEntry in BEATS_ARRAY) {
                        if (beatsEntry > 0) {
                            beatsArrayAverage += beatsEntry
                            beatsArrayCount++
                        }
                    }

                    val beatsAverage = beatsArrayAverage / beatsArrayCount
                    previousBeatsAverage = beatsAverage
                    log("beatsAverage: $beatsAverage")
                    publishSubject.onNext(Bpm(beatsAverage, currentPixelType))

                    startTime = System.currentTimeMillis()
                    beats = 0.0
                }

                PROCESSING.set(false)
            }
        }
    }

    /**
     * An empty immutable `long` array.
     */
    private val EMPTY_LONG_ARRAY = LongArray(0)

    /**
     *
     * Converts an array of object Longs to primitives.
     *
     *
     * This method returns `null` for a `null` input array.
     *
     * @param array  a `Long` array, may be `null`
     * @return a `long` array, `null` if null array input
     * @throws NullPointerException if array content is `null`
     */
    protected fun toPrimitive(array: Array<Long>?): LongArray? {
        if (array == null) {
            return null
        } else if (array.isEmpty()) {
            return EMPTY_LONG_ARRAY
        }
        val result = LongArray(array.size)
        for (i in array.indices) {
            result[i] = array[i]
        }
        return result
    }

    /**
     * An empty immutable `double` array.
     */
    private val EMPTY_DOUBLE_ARRAY = DoubleArray(0)

    /**
     *
     * Converts an array of object Doubles to primitives.
     *
     *
     * This method returns `null` for a `null` input array.
     *
     * @param array  a `Double` array, may be `null`
     * @return a `double` array, `null` if null array input
     * @throws NullPointerException if array content is `null`
     */
    protected fun toPrimitive(array: Array<Double>?): DoubleArray? {
        if (array == null) {
            return null
        } else if (array.isEmpty()) {
            return EMPTY_DOUBLE_ARRAY
        }
        val result = DoubleArray(array.size)
        for (i in array.indices) {
            result[i] = array[i]
        }
        return result
    }

    private fun getSmallestPreviewSize(width: Int, height: Int, parameters: Camera.Parameters?): Camera.Size? {

        var result: Camera.Size? = null

        parameters?.supportedPreviewSizes?.let {
            it
                    .asSequence()
                    .filter { it.width <= width && it.height <= height }
                    .forEach {
                        if (result == null) {
                            result = it
                        } else {
                            if (it.width * it.height < result!!.width * result!!.height)
                                result = it
                        }
                    }
        }

        return result
    }

    private fun cleanUp() {
        log("cleanUp")

        if (wakelock?.isHeld == true) {
            wakelock?.release()
        }

        cameraSupport?.apply {
            setPreviewCallback(null)
            stopPreview()
            release()
        }

        cameraSupport = null
    }

    private fun log(message: String?) {
        if (enableLogging)
            Log.d(TAG, "" + message)
    }

    fun setFingerDetectionListener(fingerDetectionListener:((Boolean) -> Unit)?): HeartRateOmeter {
        this.fingerDetectionListener = fingerDetectionListener
        return this
    }

    fun setChartDataListener(chartDataListener:((Int) -> Unit)?): HeartRateOmeter {
        this.chartDataListener = chartDataListener;
        return this
    }

    fun setRawDataListener(rawDataListener:((Long, Float, Float, Float) -> Unit)?): HeartRateOmeter {
        this.rawDataListener = rawDataListener;
        return this
    }
}