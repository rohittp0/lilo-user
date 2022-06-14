package com.google.ar.core.examples.java.persistentcloudanchor;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.util.Log;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.GuardedBy;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.DialogFragment;

import com.google.ar.core.Anchor;
import com.google.ar.core.Anchor.CloudAnchorState;
import com.google.ar.core.ArCoreApk;
import com.google.ar.core.Camera;
import com.google.ar.core.Config;
import com.google.ar.core.Config.CloudAnchorMode;
import com.google.ar.core.Frame;
import com.google.ar.core.PointCloud;
import com.google.ar.core.Pose;
import com.google.ar.core.Session;
import com.google.ar.core.TrackingState;
import com.google.ar.core.examples.java.common.helpers.CameraPermissionHelper;
import com.google.ar.core.examples.java.common.helpers.DisplayRotationHelper;
import com.google.ar.core.examples.java.common.helpers.FullScreenHelper;
import com.google.ar.core.examples.java.common.helpers.TrackingStateHelper;
import com.google.ar.core.examples.java.common.rendering.BackgroundRenderer;
import com.google.ar.core.examples.java.common.rendering.ObjectRenderer;
import com.google.ar.core.examples.java.common.rendering.PointCloudRenderer;
import com.google.ar.core.examples.java.persistentcloudanchor.PrivacyNoticeDialogFragment.HostResolveListener;
import com.google.ar.core.exceptions.CameraNotAvailableException;
import com.google.ar.core.exceptions.UnavailableApkTooOldException;
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException;
import com.google.ar.core.exceptions.UnavailableSdkTooOldException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * Main Activity for the Persistent Cloud Anchor Sample.
 *
 * <p>This is a simple example that shows how to host and resolve anchors using ARCore Cloud Anchors
 * API calls. This app only has at most one anchor at a time, to focus more on the cloud aspect of
 * anchors.
 */
public class CloudAnchorActivity extends AppCompatActivity implements GLSurfaceView.Renderer {
    private static final String TAG = CloudAnchorActivity.class.getSimpleName();
    private static final String EXTRA_ANCHORS_TO_RESOLVE = "persistentcloudanchor.anchors_to_resolve";
    private static final String ALLOW_SHARE_IMAGES_KEY = "ALLOW_SHARE_IMAGES";
    protected static final String PREFERENCE_FILE_KEY = "CLOUD_ANCHOR_PREFERENCES";
    protected static final String HOSTED_ANCHOR_IDS = "anchor_ids";
    protected static final String HOSTED_ANCHOR_NAMES = "anchor_names";
    protected static final String HOSTED_ANCHOR_MINUTES = "anchor_minutes";

    @NonNull
    static Intent newResolvingIntent(Context packageContext, ArrayList<String> anchorsToResolve) {
        Intent intent = new Intent(packageContext, CloudAnchorActivity.class);
        intent.putExtra(EXTRA_ANCHORS_TO_RESOLVE, anchorsToResolve);
        return intent;
    }

    // Rendering. The Renderers are created here, and initialized when the GL surface is created.
    private GLSurfaceView surfaceView;
    private final BackgroundRenderer backgroundRenderer = new BackgroundRenderer();
    private final ObjectRenderer anchorObject = new ObjectRenderer();
    private final PointCloudRenderer pointCloudRenderer = new PointCloudRenderer();

    private boolean installRequested;

    // Temporary matrices allocated here to reduce number of allocations for each frame.
    private final float[] anchorMatrix = new float[16];
    private final float[] viewMatrix = new float[16];
    private final float[] projectionMatrix = new float[16];

    // Locks needed for synchronization
    private final Object anchorLock = new Object();

    // Tap handling and UI.
    private DisplayRotationHelper displayRotationHelper;
    private final TrackingStateHelper trackingStateHelper = new TrackingStateHelper(this);
    private TextView debugText;
    private TextView userMessageText;
    private SharedPreferences sharedPreferences;

    private Session session;

    @GuardedBy("anchorLock")
    private final List<Anchor> resolvedAnchors = new ArrayList<>();

    @GuardedBy("anchorLock")
    private List<String> unresolvedAnchorIds = new ArrayList<>();

    private CloudAnchorManager cloudAnchorManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.cloud_anchor);
        surfaceView = findViewById(R.id.surfaceview);
        displayRotationHelper = new DisplayRotationHelper(this);

        // Set up renderer.
        surfaceView.setPreserveEGLContextOnPause(true);
        surfaceView.setEGLContextClientVersion(2);
        surfaceView.setEGLConfigChooser(8, 8, 8, 8, 16, 0); // Alpha used for plane blending.
        surfaceView.setRenderer(this);
        surfaceView.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
        surfaceView.setWillNotDraw(false);
        installRequested = false;

        // Initialize UI components.
        debugText = findViewById(R.id.debug_message);
        userMessageText = findViewById(R.id.user_message);

        showPrivacyDialog();
    }

    private void showPrivacyDialog() {
        sharedPreferences = getSharedPreferences(PREFERENCE_FILE_KEY, Context.MODE_PRIVATE);

        if (!sharedPreferences.getBoolean(ALLOW_SHARE_IMAGES_KEY, false)) {
            showNoticeDialog(this::onPrivacyAcceptedForResolve);
        } else {
            onPrivacyAcceptedForResolve();
        }
    }

    @Override
    protected void onDestroy() {
        if (session != null) {
            session.close();
            session = null;
        }

        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (sharedPreferences.getBoolean(ALLOW_SHARE_IMAGES_KEY, false)) {
            createSession();
        }
        surfaceView.onResume();
        displayRotationHelper.onResume();
    }

    private void createSession() {
        if (session == null) {
            Exception exception = null;
            int messageId = -1;
            try {
                switch (ArCoreApk.getInstance().requestInstall(this, !installRequested)) {
                    case INSTALL_REQUESTED:
                        installRequested = true;
                        return;
                    case INSTALLED:
                        break;
                }
                // ARCore requires camera permissions to operate. If we did not yet obtain runtime
                // permission on Android M and above, now is a good time to ask the user for it.
                if (!CameraPermissionHelper.hasCameraPermission(this)) {
                    CameraPermissionHelper.requestCameraPermission(this);
                    return;
                }
                session = new Session(this);
                cloudAnchorManager = new CloudAnchorManager(session);
            } catch (UnavailableArcoreNotInstalledException e) {
                messageId = R.string.arcore_unavailable;
                exception = e;
            } catch (UnavailableApkTooOldException e) {
                messageId = R.string.arcore_too_old;
                exception = e;
            } catch (UnavailableSdkTooOldException e) {
                messageId = R.string.arcore_sdk_too_old;
                exception = e;
            } catch (Exception e) {
                messageId = R.string.arcore_exception;
                exception = e;
            }

            if (exception != null) {
                userMessageText.setText(messageId);
                debugText.setText(messageId);
                Log.e(TAG, "Exception creating session", exception);
                return;
            }

            // Create default config and check if supported.
            Config config = new Config(session);
            config.setCloudAnchorMode(CloudAnchorMode.ENABLED);
            session.configure(config);
        }

        // Note that order matters - see the note in onPause(), the reverse applies here.
        try {
            session.resume();
        } catch (CameraNotAvailableException e) {
            userMessageText.setText(R.string.camera_unavailable);
            debugText.setText(R.string.camera_unavailable);
            session = null;
            cloudAnchorManager = null;
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        if (session != null) {
            // Note that the order matters - GLSurfaceView is paused first so that it does not try
            // to query the session. If Session is paused before GLSurfaceView, GLSurfaceView may
            // still call session.update() and get a SessionPausedException.
            displayRotationHelper.onPause();
            surfaceView.onPause();
            session.pause();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);

        if (!CameraPermissionHelper.hasCameraPermission(this)) {
            Toast.makeText(this, "Camera permission is needed to run this application", Toast.LENGTH_LONG)
                    .show();
            if (!CameraPermissionHelper.shouldShowRequestPermissionRationale(this)) {
                // Permission denied with checking "Do not ask again".
                CameraPermissionHelper.launchPermissionSettings(this);
            }
            finish();
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        FullScreenHelper.setFullScreenOnWindowFocusChanged(this, hasFocus);
    }
    

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        GLES20.glClearColor(0.1f, 0.1f, 0.1f, 1.0f);

        // Prepare the rendering objects. This involves reading shaders, so may throw an IOException.
        try {
            // Create the texture and pass it to ARCore session to be filled during update().
            backgroundRenderer.createOnGlThread(this);
            pointCloudRenderer.createOnGlThread(this);

            anchorObject.createOnGlThread(this, "models/anchor.obj", "models/anchor.png");
            anchorObject.setMaterialProperties(0.0f, 0.75f, 0.1f, 0.5f);

        } catch (IOException ex) {
            Log.e(TAG, "Failed to read an asset file", ex);
        }
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        displayRotationHelper.onSurfaceChanged(width, height);
        GLES20.glViewport(0, 0, width, height);
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        // Clear screen to notify driver it should not load any pixels from previous frame.
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);

        if (session == null) {
            return;
        }
        // Notify ARCore session that the view size changed so that the perspective matrix and
        // the video background can be properly adjusted.
        displayRotationHelper.updateSessionIfNeeded(session);

        try {
            session.setCameraTextureName(backgroundRenderer.getTextureId());

            // Obtain the current frame from ARSession. When the configuration is set to
            // UpdateMode.BLOCKING (it is by default), this will throttle the rendering to the
            // camera framerate.
            Frame frame = session.update();
            Camera camera = frame.getCamera();
            TrackingState cameraTrackingState = camera.getTrackingState();

            // Notify the cloudAnchorManager of all the updates.
            cloudAnchorManager.onUpdate();

            // If frame is ready, render camera preview image to the GL surface.
            backgroundRenderer.draw(frame);

            // Keep the screen unlocked while tracking, but allow it to lock when tracking stops.
            trackingStateHelper.updateKeepScreenOnFlag(camera.getTrackingState());

            // If not tracking, don't draw 3d objects.
            if (cameraTrackingState == TrackingState.PAUSED) {
                return;
            }

            // Get camera and projection matrices.
            camera.getViewMatrix(viewMatrix, 0);
            camera.getProjectionMatrix(projectionMatrix, 0, 0.1f, 100.0f);

            // Visualize tracked points.
            // Use try-with-resources to automatically release the point cloud.
            try (PointCloud pointCloud = frame.acquirePointCloud()) {
                pointCloudRenderer.update(pointCloud);
                pointCloudRenderer.draw(viewMatrix, projectionMatrix);
            }

            float[] colorCorrectionRgba = new float[4];
            float scaleFactor = 1.0f;
            frame.getLightEstimate().getColorCorrection(colorCorrectionRgba, 0);

            synchronized (anchorLock) {
                Pose anchorPose;
                
                for (Anchor resolvedAnchor : resolvedAnchors) {
                    // Update the poses of resolved anchors that can be drawn and render them.
                    if (resolvedAnchor != null
                            && resolvedAnchor.getTrackingState() == TrackingState.TRACKING) {
                        // Get the current pose of an Anchor in world space. The Anchor pose is updated
                        // during calls to session.update() as ARCore refines its estimate of the world.
                        anchorPose = resolvedAnchor.getPose();
                        anchorPose.toMatrix(anchorMatrix, 0);
                        // Update and draw the model and its shadow.
                        drawAnchor(anchorMatrix, scaleFactor, colorCorrectionRgba);
                    }
                }
            }

        } catch (Throwable t) {
            // Avoid crashing the application due to unhandled exceptions.
            Log.e(TAG, "Exception on the OpenGL thread", t);
        }
    }
    

    private void drawAnchor(float[] anchorMatrix, float scaleFactor, float[] colorCorrectionRgba) {
        anchorObject.updateModelMatrix(anchorMatrix, scaleFactor);
        anchorObject.draw(viewMatrix, projectionMatrix, colorCorrectionRgba);
    }
    

    /**
     * Adds a new anchor to the set of resolved anchors.
     */
    private void setAnchorAsResolved(@NonNull Anchor newAnchor) {
        synchronized (anchorLock) {
            if (unresolvedAnchorIds.contains(newAnchor.getCloudAnchorId())) {
                resolvedAnchors.add(newAnchor);
                unresolvedAnchorIds.remove(newAnchor.getCloudAnchorId());
            }
        }
    }
    
    private void onPrivacyAcceptedForResolve() {
        if (!sharedPreferences.edit().putBoolean(ALLOW_SHARE_IMAGES_KEY, true).commit()) {
            throw new AssertionError("Could not save the user preference to SharedPreferences!");
        }
        createSession();
        ResolveListener resolveListener = new ResolveListener();
        synchronized (anchorLock) {
            unresolvedAnchorIds = getIntent().getStringArrayListExtra(EXTRA_ANCHORS_TO_RESOLVE);
            debugText.setText(getString(R.string.debug_resolving_processing, unresolvedAnchorIds.size()));
            // Encourage the user to look at a previously mapped area.
            userMessageText.setText(R.string.resolving_processing);
            Log.i(
                    TAG,
                    String.format(
                            "Attempting to resolve %d anchor(s): %s",
                            unresolvedAnchorIds.size(), unresolvedAnchorIds));
            for (String cloudAnchorId : unresolvedAnchorIds) {
                cloudAnchorManager.resolveCloudAnchor(cloudAnchorId, resolveListener);
            }
        }
    }

    /* Listens for a resolved anchor. */
    private final class ResolveListener implements CloudAnchorManager.CloudAnchorListener {

        @Override
        public void onComplete(Anchor anchor) {
            runOnUiThread(
                    () -> {
                        CloudAnchorState state = anchor.getCloudAnchorState();
                        if (state.isError()) {
                            Log.e(TAG, "Error hosting a cloud anchor, state " + state);
                            userMessageText.setText(getString(R.string.resolving_error, state));
                            return;
                        }
                        setAnchorAsResolved(anchor);
                        userMessageText.setText(getString(R.string.resolving_success));
                        synchronized (anchorLock) {
                            if (unresolvedAnchorIds.isEmpty()) {
                                debugText.setText(getString(R.string.debug_resolving_success));
                            } else {
                                Log.i(
                                        TAG,
                                        String.format(
                                                "Attempting to resolve %d anchor(s): %s",
                                                unresolvedAnchorIds.size(), unresolvedAnchorIds));
                                debugText.setText(
                                        getString(R.string.debug_resolving_processing, unresolvedAnchorIds.size()));
                            }
                        }
                    });
        }
    }

    public void showNoticeDialog(HostResolveListener listener) {
        DialogFragment dialog = PrivacyNoticeDialogFragment.createDialog(listener);
        dialog.show(getSupportFragmentManager(), PrivacyNoticeDialogFragment.class.getName());
    }
}
