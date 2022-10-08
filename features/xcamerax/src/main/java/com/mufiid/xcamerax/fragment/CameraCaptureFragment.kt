package com.mufiid.xcamerax.fragment

import android.annotation.SuppressLint
import android.app.ProgressDialog
import android.content.ContentValues
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.concurrent.futures.await
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.util.Consumer
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.whenCreated
import androidx.navigation.NavController
import androidx.navigation.Navigation
import com.mufiid.xcamerax.R
import com.mufiid.xcamerax.databinding.FragmentCameraCaptureBinding
import com.mufiid.xcamerax.model.CameraCapability
import com.mufiid.xcamerax.state.CameraCaptureUIState
import com.mufiid.xcamerax.utils.getAspectRatio
import com.mufiid.xcamerax.utils.getAspectRatioString
import com.mufiid.xcamerax.utils.getFormattedStopWatchTime
import com.mufiid.xcamerax.utils.getNameString
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class CameraCaptureFragment : Fragment() {

    private val binding: FragmentCameraCaptureBinding by lazy {
        FragmentCameraCaptureBinding.inflate(layoutInflater)
    }

    private val captureLiveStatus = MutableLiveData<String>()
    private val captureLiveTimer = MutableLiveData<Long>()
    private val progressDialog: ProgressDialog by lazy {
        ProgressDialog(requireContext())
    }

    /** Host's navigation controller */
    private val navController: NavController by lazy {
        Navigation.findNavController(requireActivity(), R.id.fragment_container)
    }

    private val cameraCapabilities = mutableListOf<CameraCapability>()

    private lateinit var videoCapture: VideoCapture<Recorder>
    private var currentRecording: Recording? = null
    private lateinit var recordingState: VideoRecordEvent

    // Camera UI  states and inputs
    private var cameraIndex = 0

    private val mainThreadExecutor by lazy {
        ContextCompat.getMainExecutor(requireContext())
    }

    private var enumerationDeferred: Deferred<Unit>? = null

    // main cameraX capture functions
    /**
     *   Always bind preview + video capture use case combinations in this sample
     *   (VideoCapture can work on its own). The function should always execute on
     *   the main thread.
     */
    private fun bindCaptureUseCase() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val cameraSelector = getCameraSelector(cameraIndex)

            // create the user required QualitySelector (video resolution): we know this is
            // supported, a valid qualitySelector will be created.
            val quality = Quality.FHD
            val qualitySelector = QualitySelector.from(quality)

            binding.previewView.updateLayoutParams<ConstraintLayout.LayoutParams> {
                val orientation = this@CameraCaptureFragment.resources.configuration.orientation
                dimensionRatio = quality.getAspectRatioString(
                    quality,
                    (orientation == Configuration.ORIENTATION_PORTRAIT)
                )
            }

            val preview = Preview.Builder()
                .setTargetAspectRatio(quality.getAspectRatio(quality))
                .build().apply {
                    setSurfaceProvider(binding.previewView.surfaceProvider)
                }

            // build a recorder, which can:
            //   - record video/audio to MediaStore(only shown here), File, ParcelFileDescriptor
            //   - be used create recording(s) (the recording performs recording)
            val recorder = Recorder.Builder()
                .setQualitySelector(qualitySelector)
                .build()
            videoCapture = VideoCapture.withOutput(recorder)

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    viewLifecycleOwner,
                    cameraSelector,
                    videoCapture,
                    preview
                )
            } catch (exc: Exception) {
                // we are on main thread, let's reset the controls on the UI.
                Log.e(TAG, "Use case binding failed", exc)
                resetUIAndState("bindToLifecycle failed: $exc")
            }
            enableUI(true)
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    private val contentValuesVideoOutput: ContentValues
        get() {
            val name = SimpleDateFormat(FILENAME_FORMAT, Locale.US)
                .format(System.currentTimeMillis())
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                    put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/CameraX-Video")
                }
            }
            return contentValues
        }

    // create MediaStoreOutputOptions for our recorder: resulting our recording!
    private val mediaStoreOutput: MediaStoreOutputOptions
        get() {
            return MediaStoreOutputOptions.Builder(
                requireActivity().contentResolver,
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            ).setContentValues(contentValuesVideoOutput).build()
        }

    /**
     * Kick start the video recording
     *   - config Recorder to capture to MediaStoreOutput
     *   - register RecordEvent Listener
     *   - apply audio request from user
     *   - start recording!
     * After this function, user could start/pause/resume/stop recording and application listens
     * to VideoRecordEvent for the current recording status.
     */
    @SuppressLint("MissingPermission")
    private fun startRecording() {
        // configure Recorder and Start recording to the mediaStoreOutput.
        currentRecording = videoCapture.output
            .prepareRecording(requireActivity(), mediaStoreOutput)
            .withAudioEnabled()
            .start(mainThreadExecutor, captureListener)
        Log.i(TAG, "Recording started")
    }

    /**
     * CaptureEvent listener.
     */
    private val captureListener = Consumer<VideoRecordEvent> { event ->
        // cache the recording state
        if (event !is VideoRecordEvent.Status)
            recordingState = event

        updateUI(event)

        if (event is VideoRecordEvent.Finalize) {
            // display the captured video
            lifecycleScope.launch {
                /*navController.navigate(
                    CameraCaptureFragmentDirections.actionCameraCaptureFragmentToCameraPreviewFragment(
                        event.outputResults.outputUri
                    )
                )*/
            }
        }
    }

    /**
     * Retrieve the asked camera's type(lens facing type). In this sample, only 2 types:
     *   idx is even number:  CameraSelector.LENS_FACING_BACK
     *          odd number:   CameraSelector.LENS_FACING_FRONT
     */
    private fun getCameraSelector(idx: Int): CameraSelector {
        if (cameraCapabilities.size == 0) {
            Log.i(TAG, "Error: This device does not have any camera, bailing out")
            requireActivity().finish()
        }
        return (cameraCapabilities[idx % cameraCapabilities.size].camSelector)
    }

    /**
     * Query and cache this platform's camera capabilities, run only once.
     */
    init {
        enumerationDeferred = lifecycleScope.async {
            whenCreated {
                val provider = ProcessCameraProvider.getInstance(requireContext()).await()

                provider.unbindAll()
                for (camSelector in arrayOf(
                    CameraSelector.DEFAULT_BACK_CAMERA, CameraSelector.DEFAULT_FRONT_CAMERA
                )) {
                    try {
                        // just get the camera.cameraInfo to query capabilities
                        // we are not binding anything here.
                        if (provider.hasCamera(camSelector)) {
                            val camera = provider.bindToLifecycle(requireActivity(), camSelector)
                            QualitySelector
                                .getSupportedQualities(camera.cameraInfo)
                                .filter { quality ->
                                    listOf(Quality.UHD, Quality.FHD, Quality.HD, Quality.SD)
                                        .contains(quality)
                                }.also {
                                    cameraCapabilities.add(CameraCapability(camSelector, it))
                                }
                        }
                    } catch (exc: java.lang.Exception) {
                        Log.e(TAG, "Camera Face $camSelector is not supported")
                    }
                }
            }
        }
    }

    /**
     * One time initialize for CameraFragment (as a part of fragment layout's creation process).
     * This function performs the following:
     *   - initialize but disable all UI controls except the Quality selection.
     *   - set up the Quality selection recycler view.
     *   - bind use cases to a lifecycle camera, enable UI controls.
     */
    private fun initCameraFragment() {
        initializeUI()
        viewLifecycleOwner.lifecycleScope.launch {
            if (enumerationDeferred != null) {
                enumerationDeferred?.await()
                enumerationDeferred = null
            }
        }
        bindCaptureUseCase()
    }

    /**
     * Initialize UI. Preview and Capture actions are configured in this function.
     * Note that preview and capture are both initialized either by UI or CameraX callbacks
     * (except the very 1st time upon entering to this fragment in onCreateView()
     */
    @SuppressLint("ClickableViewAccessibility", "MissingPermission")
    private fun initializeUI() {
        binding.cameraButton.apply {
            setOnClickListener {
                cameraIndex = (cameraIndex + 1) % cameraCapabilities.size
                // camera device change is in effect instantly:
                //   - reset quality selection
                //   - restart preview
                enableUI(false)
                viewLifecycleOwner.lifecycleScope.launch {
                    bindCaptureUseCase()
                }
            }
            isEnabled = false
        }

        // React to user touching the capture button
        binding.captureButton.apply {
            setOnClickListener {
                if (!this@CameraCaptureFragment::recordingState.isInitialized ||
                    recordingState is VideoRecordEvent.Finalize
                ) {
                    enableUI(false)  // Our eventListener will turn on the Recording UI.
                    startRecording()
                } else {
                    when (recordingState) {
                        is VideoRecordEvent.Start -> {
                            currentRecording?.pause()
                            binding.stopButton.visibility = View.VISIBLE
                        }
                        is VideoRecordEvent.Pause -> currentRecording?.resume()
                        is VideoRecordEvent.Resume -> currentRecording?.pause()
                        else -> throw IllegalStateException("recordingState in unknown state")
                    }
                }
            }
            isEnabled = false
        }

        binding.stopButton.apply {
            setOnClickListener {
                progressDialog.apply {
                    setMessage("Sedang menyimpan video...")
                }.show()
                // stopping: hide it after getting a click before we go to viewing fragment
                binding.stopButton.visibility = View.INVISIBLE
                if (currentRecording == null || recordingState is VideoRecordEvent.Finalize) {
                    return@setOnClickListener
                }

                val recording = currentRecording
                if (recording != null) {
                    recording.stop()
                    currentRecording = null
                }
                binding.captureButton.background =
                    ContextCompat.getDrawable(requireContext(), R.drawable.ic_record)
            }
            // ensure the stop button is initialized disabled & invisible
            visibility = View.INVISIBLE
            isEnabled = false
        }

        captureLiveStatus.observe(viewLifecycleOwner) {
            binding.captureStatus.apply {
                post { text = it }
            }
        }

        captureLiveTimer.observe(viewLifecycleOwner) {
            println("------> LONG: $it")
            binding.timer.apply {
                post { text = getFormattedStopWatchTime(seconds = it.toInt()) }
            }
        }

        captureLiveStatus.value = ""
    }

    /**
     * UpdateUI according to CameraX VideoRecordEvent type:
     *   - user starts capture.
     *   - this app disables all UI selections.
     *   - this app enables capture run-time UI (pause/resume/stop).
     *   - user controls recording with run-time UI, eventually tap "stop" to end.
     *   - this app informs CameraX recording to stop with recording.stop() (or recording.close()).
     *   - CameraX notify this app that the recording is indeed stopped, with the Finalize event.
     *   - this app starts VideoViewer fragment to view the captured result.
     */
    private fun updateUI(event: VideoRecordEvent) {
        val state = if (event is VideoRecordEvent.Status) {
            recordingState.getNameString()
        } else {
            event.getNameString()
        }
        when (event) {
            is VideoRecordEvent.Status -> {
                // placeholder: we update the UI with new status after this when() block,
                // nothing needs to do here.
            }
            is VideoRecordEvent.Start -> {
                showUI(CameraCaptureUIState.RECORDING, event.getNameString())
            }
            is VideoRecordEvent.Finalize -> {
                showUI(CameraCaptureUIState.FINALIZED, event.getNameString())
            }
            is VideoRecordEvent.Pause -> {
                binding.captureButton.background =
                    ContextCompat.getDrawable(requireContext(), R.drawable.ic_record)
            }
            is VideoRecordEvent.Resume -> {
                binding.captureButton.background =
                    ContextCompat.getDrawable(requireContext(), R.drawable.ic_pause)
            }
        }

        val stats = event.recordingStats
        val size = stats.numBytesRecorded / 1000
        val time = java.util.concurrent.TimeUnit.NANOSECONDS.toSeconds(stats.recordedDurationNanos)
        var text = "${state.uppercase()}\nrecorded ${size}KB"
        if (event is VideoRecordEvent.Finalize) {
            text = "${text}\nFile saved to: ${event.outputResults.outputUri}"
            progressDialog.dismiss()
        }

        captureLiveStatus.value = text
        captureLiveTimer.value = time
        Log.i(TAG, "recording event: $text")
    }

    /**
     * Enable/disable UI:
     *    User could select the capture parameters when recording is not in session
     *    Once recording is started, need to disable able UI to avoid conflict.
     */
    private fun enableUI(enable: Boolean) {
        arrayOf(
            binding.cameraButton,
            binding.captureButton,
            binding.stopButton,
        ).forEach {
            it.isEnabled = enable
        }
        // disable the camera button if no device to switch
        if (cameraCapabilities.size <= 1) {
            binding.cameraButton.isEnabled = false
        }
    }

    /**
     * initialize UI for recording:
     *  - at recording: hide audio, qualitySelection,change camera UI; enable stop button
     *  - otherwise: show all except the stop button
     */
    private fun showUI(state: CameraCaptureUIState, status: String = "idle") {
        binding.let {
            when (state) {
                CameraCaptureUIState.IDLE -> {
                    it.captureButton.background =
                        ContextCompat.getDrawable(requireContext(), R.drawable.ic_record)
                    it.stopButton.visibility = View.INVISIBLE
                    it.cameraButton.visibility = View.VISIBLE
                    it.timer.visibility = View.INVISIBLE
                }
                CameraCaptureUIState.RECORDING -> {
                    it.cameraButton.visibility = View.INVISIBLE
                    it.timer.visibility = View.VISIBLE
                    it.captureButton.background =
                        ContextCompat.getDrawable(requireContext(), R.drawable.ic_pause)
                    it.captureButton.isEnabled = true
                    it.stopButton.visibility = View.VISIBLE
                    it.stopButton.isEnabled = true
                }
                CameraCaptureUIState.FINALIZED -> {
                    it.captureButton.background =
                        ContextCompat.getDrawable(requireContext(), R.drawable.ic_record)
                    it.stopButton.visibility = View.INVISIBLE
                }
                else -> {
                    val errorMsg = "Error: showUI($state) is not supported"
                    Log.e(TAG, errorMsg)
                    return
                }
            }
            it.captureStatus.text = status.uppercase()
        }
    }

    /**
     * ResetUI (restart):
     *    in case binding failed, let's give it another change for re-try. In future cases
     *    we might fail and user get notified on the status
     */
    private fun resetUIAndState(reason: String) {
        enableUI(true)
        showUI(CameraCaptureUIState.IDLE, reason)
        cameraIndex = 0
    }

    override fun onResume() {
        super.onResume()
        resetUIAndState("Resume lifecycle")
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initCameraFragment()
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return binding.root
    }

    companion object {
        // default Quality selection if no input from UI
        val TAG: String = CameraCaptureFragment::class.java.simpleName
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
    }
}