

package com.google.ar.core.examples.java.persistentcloudanchor;

import androidx.annotation.NonNull;

import com.google.ar.core.Anchor;
import com.google.ar.core.Anchor.CloudAnchorState;
import com.google.ar.core.Session;
import com.google.common.base.Preconditions;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * A helper class to handle all the Cloud Anchors logic, and add a callback-like mechanism on top of
 * the existing ARCore API.
 */
class CloudAnchorManager {

  /** Listener for the results of a host operation. */
  interface CloudAnchorListener {

    /** This method is invoked when the results of a Cloud Anchor operation are available. */
    void onComplete(Anchor anchor);
  }

  private final Session session;
  private final Map<Anchor, CloudAnchorListener> pendingAnchors = new HashMap<>();

  CloudAnchorManager(Session session) {
    this.session = Preconditions.checkNotNull(session);
  }

  /** Resolves an anchor. The {@code listener} will be invoked when the results are available. */
  synchronized void resolveCloudAnchor(String anchorId, CloudAnchorListener listener) {
    Preconditions.checkNotNull(listener, "The listener cannot be null.");
    Anchor newAnchor = session.resolveCloudAnchor(anchorId);
    pendingAnchors.put(newAnchor, listener);
  }

  /** Should be called after a {@link Session#update()} call. */
  synchronized void onUpdate() {
    Preconditions.checkNotNull(session, "The session cannot be null.");
    for (Iterator<Map.Entry<Anchor, CloudAnchorListener>> it = pendingAnchors.entrySet().iterator();
        it.hasNext(); ) {
      Map.Entry<Anchor, CloudAnchorListener> entry = it.next();
      Anchor anchor = entry.getKey();
      if (isReturnableState(anchor.getCloudAnchorState())) {
        CloudAnchorListener listener = entry.getValue();
        listener.onComplete(anchor);
        it.remove();
      }
    }
  }

  /** Clears any currently registered listeners, so they won't be called again. */
  synchronized void clearListeners() {
    pendingAnchors.clear();
  }

  @org.jetbrains.annotations.Contract(pure = true)
  private static boolean isReturnableState(@NonNull CloudAnchorState cloudState) {
    switch (cloudState) {
      case NONE:
      case TASK_IN_PROGRESS:
        return false;
      default:
        return true;
    }
  }
}
