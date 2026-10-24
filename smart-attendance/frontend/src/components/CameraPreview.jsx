import React, { useState, useEffect, useRef, useCallback } from "react";

const MAX_CAPTURE_WIDTH = 640;
const MAX_CAPTURE_HEIGHT = 480;

/**
 * Responsive camera preview.
 *
 * Optional props (all backwards compatible with existing call sites):
 *   facingMode  "user" (default: face registration) or "environment" (classroom)
 *   showSwitch  render a front/back switch button when the device has 2 cameras
 *   active      pause/resume the stream (false stops the tracks completely)
 *   hint        short guidance text shown over the preview
 *   compact     shorter preview box
 */
function CameraPreview({
  onImageCaptured,
  onError,
  facingMode: facingModeProp = "environment",
  showSwitch = false,
  active = true,
  hint = null,
  compact = false,
}) {
  const videoRef = useRef(null);
  const streamRef = useRef(null);
  const [hasPermission, setHasPermission] = useState(null);
  const [permissionError, setPermissionError] = useState(null);
  const [isCapturing, setIsCapturing] = useState(false);
  const [photo, setPhoto] = useState(null);
  const [facing, setFacing] = useState(facingModeProp);
  const [retryKey, setRetryKey] = useState(0);

  const stopStream = useCallback(() => {
    if (streamRef.current) {
      streamRef.current.getTracks().forEach((track) => track.stop());
      streamRef.current = null;
    }
  }, []);

  const startStream = useCallback(async () => {
    let cancelled = false;
    async function initCamera() {
      try {
        if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) {
          throw new Error("Camera is not supported in this browser. Use a recent Chrome, Edge or Safari, or upload a photo instead.");
        }
        const mediaStream = await navigator.mediaDevices.getUserMedia({
          video: {
            facingMode,
            width: { ideal: MAX_CAPTURE_WIDTH },
            height: { ideal: MAX_CAPTURE_HEIGHT },
          },
          audio: false,
        });
        if (cancelled || !active) {
          mediaStream.getTracks().forEach((t) => t.stop());
          return;
        }
        stopStream();
        streamRef.current = mediaStream;
        setPermissionError(null);
        setHasPermission(true);
        if (videoRef.current) {
          videoRef.current.srcObject = mediaStream;
          // Safari needs an explicit play() once the stream is attached
          const p = videoRef.current.play();
          if (p && p.catch) p.catch(() => {});
        }
      } catch (err) {
        if (cancelled) return;
        const name = err && err.name;
        let text = err.message || "Camera error";
        if (name === "NotAllowedError" || name === "SecurityError") {
          text = "Camera permission was denied. Allow camera access in your browser settings, then press Retry.";
        } else if (name === "NotFoundError" || name === "OverconstrainedError") {
          text = "No usable camera was found on this device. You can upload a photo instead.";
        } else if (name === "NotReadableError") {
          text = "The camera is already in use by another app. Close it and press Retry.";
        }
        setHasPermission(false);
        setPermissionError(text);
        if (onError) onError(new Error(text));
      }
    }
    initCamera();
    return () => { cancelled = true; };
  }, [facing, facingMode, onError, stopStream, active]);

  useEffect(() => {
    if (!active) {
      stopStream();
      setHasPermission(null);
      return undefined;
    }
    let cleanup;
    const run = async () => { cleanup = await startStream(); };
    run();
    return () => {
      if (cleanup) cleanup();
      stopStream();
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [active, facing, facingMode, retryKey]);

  const handleCapture = () => {
    const video = videoRef.current;
    if (!video || !video.srcObject || video.videoWidth === 0) {
      const error = new Error("The camera is not ready yet. Wait a moment and try again.");
      if (onError) onError(error);
      return;
    }
    setIsCapturing(true);
    try {
      // A fixed 4:3 box keeps the preview and the capture identical on every
      // screen size, so nothing is cropped away or padded with blank areas.
      const outW = MAX_CAPTURE_WIDTH;
      const outH = MAX_CAPTURE_HEIGHT;
      const canvas = document.createElement("canvas");
      canvas.width = outW;
      canvas.height = outH;

      const context = canvas.getContext("2d");
      if (!context) throw new Error("Could not get canvas context");

      // cover-fit the video frame into the 4:3 canvas
      const srcAspect = video.videoWidth / video.videoHeight;
      const dstAspect = outW / outH;
      let sw = video.videoWidth;
      let sh = video.videoHeight;
      let sx = 0;
      let sy = 0;
      if (srcAspect > dstAspect) {
        sw = video.videoHeight * dstAspect;
        sx = (video.videoWidth - sw) / 2;
      } else {
        sh = video.videoWidth / dstAspect;
        sy = (video.videoHeight - sh) / 2;
      }
      context.drawImage(video, sx, sy, sw, sh, 0, 0, outW, outH);

      const dataURL = canvas.toDataURL("image/jpeg", 0.85);
      const base64String = dataURL.split(",")[1];

      setPhoto(base64String);
      setIsCapturing(false);
      if (onImageCaptured) onImageCaptured(base64String);
    } catch (err) {
      setIsCapturing(false);
      if (onError) onError(err);
    }
  };

  const retake = () => {
    setPhoto(null);
    setIsCapturing(false);
  };

  const switchCamera = () => {
    setPhoto(null);
    setFacing((f) => (f === "user" ? "environment" : "user"));
  };

  const boxHeight = compact ? 180 : MAX_CAPTURE_HEIGHT * 0.62;

  return (
    <div
      style={{
        border: "1px solid #dee2e6",
        borderRadius: "8px",
        overflow: "hidden",
        position: "relative",
        background: "#111",
        width: "100%",
        maxWidth: "100%",
      }}
    >
      <video
        ref={videoRef}
        autoPlay
        playsInline
        muted
        style={{
          display: hasPermission && !photo ? "block" : "none",
          width: "100%",
          height: `${boxHeight}px`,
          objectFit: "cover",
          background: "#111",
        }}
      />

      {!hasPermission && !photo && (
        <div
          style={{
            width: "100%",
            minHeight: `${boxHeight}px`,
            background: "#e9ecef",
            display: "flex",
            flexDirection: "column",
            alignItems: "center",
            justifyContent: "center",
            gap: "0.4rem",
            color: "#495057",
            fontSize: "0.85rem",
            padding: "1rem",
            textAlign: "center",
          }}
        >
          <span style={{ fontSize: "1.6rem" }}>📷</span>
          <span>{permissionError || "Camera access required"}</span>
          <button
            type="button"
            onClick={() => { setPermissionError(null); setHasPermission(null); setRetryKey((k) => k + 1); }}
            style={{
              marginTop: "0.4rem", padding: "0.35rem 0.9rem", fontSize: "0.8rem",
              border: "1px solid #adb5bd", background: "#fff", borderRadius: "6px", cursor: "pointer",
            }}
          >
            Retry
          </button>
        </div>
      )}

      {hasPermission && hint && !photo && (
        <div
          style={{
            position: "absolute", top: "0.5rem", left: "0.5rem", right: "0.5rem",
            background: "rgba(0,0,0,0.55)", color: "#fff", fontSize: "0.75rem",
            padding: "0.35rem 0.6rem", borderRadius: "6px", textAlign: "center",
          }}
        >
          {hint}
        </div>
      )}

      {hasPermission && !photo && (
        <div style={{ position: "absolute", bottom: "0.75rem", left: 0, right: 0, textAlign: "center" }}>
          <button
            type="button"
            onClick={handleCapture}
            disabled={isCapturing}
            style={{
              background: "rgba(255, 255, 255, 0.92)",
              border: "2px solid #27ae60",
              color: "#27ae60",
              borderRadius: "22px",
              padding: "0.55rem 1.6rem",
              fontSize: "0.9rem",
              cursor: "pointer",
              fontWeight: "bold",
              minWidth: "120px",
            }}
          >
            {isCapturing ? "Capturing..." : "📸 Capture"}
          </button>
        </div>
      )}

      {showSwitch && hasPermission && !photo && (
        <button
          type="button"
          onClick={switchCamera}
          title={facing === "user" ? "Switch to rear camera" : "Switch to front camera"}
          style={{
            position: "absolute", top: "0.5rem", right: "0.5rem",
            background: "rgba(255,255,255,0.9)", border: "none", borderRadius: "16px",
            padding: "0.3rem 0.7rem", fontSize: "0.8rem", cursor: "pointer", zIndex: 2,
          }}
        >
          ⟲ Flip
        </button>
      )}

      {photo && (
        <div style={{ position: "relative" }}>
          <img
            src={`data:image/jpeg;base64,${photo}`}
            style={{ width: "100%", height: `${boxHeight}px`, objectFit: "cover", display: "block" }}
            alt="Captured image"
          />
          <button
            type="button"
            onClick={retake}
            style={{
              position: "absolute",
              top: "0.5rem",
              right: "0.5rem",
              background: "rgba(231, 76, 60, 0.9)",
              color: "white",
              border: "none",
              borderRadius: "16px",
              padding: "0.3rem 0.8rem",
              fontSize: "0.75rem",
              cursor: "pointer",
            }}
          >
            Retake
          </button>
        </div>
      )}
    </div>
  );
}

export default CameraPreview;