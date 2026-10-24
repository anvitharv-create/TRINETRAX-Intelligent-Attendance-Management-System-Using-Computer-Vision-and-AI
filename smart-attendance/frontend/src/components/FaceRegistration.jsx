import React, { useRef, useState, useEffect } from "react";
import { recognitionApi } from "../services/api";
import { COLORS, buttonStyle, smallButton, Banner } from "../theme";
import CameraPreview from "./CameraPreview";

const STEPS = [
  {
    pose: "front",
    label: "Front",
    instruction: "Look straight at the camera, keep a neutral expression.",
  },
  {
    pose: "left",
    label: "Slight left",
    instruction: "Turn your head slightly to your left (about 20 degrees), still visible to the camera.",
  },
  {
    pose: "right",
    label: "Slight right",
    instruction: "Turn your head slightly to your right (about 20 degrees), still visible to the camera.",
  },
];

const isBlank = (v) => !v || !String(v).trim();

/**
 * Guided three-angle face registration.
 *
 * Every step is validated by the server (real face detection, exactly one face,
 * 128-d embedding). Nothing is registered until all three angles are valid, and
 * the whole set is written in one atomic backend call, so a partial
 * registration can never be reported as a success.
 */
export default function FaceRegistration({ studentId, name, onDone, onClose }) {
  const [step, setStep] = useState(0);
  const [shots, setShots] = useState({}); // pose -> base64
  const [mode, setMode] = useState("camera"); // camera | upload
  const [message, setMessage] = useState(null);
  const [busy, setBusy] = useState(false);
  const [done, setDone] = useState(null);
  const fileRef = useRef(null);

  const current = STEPS[step];
  const currentShot = shots[current.pose];
  const capturedCount = STEPS.filter((s) => shots[s.pose]).length;
  const allCaptured = capturedCount === STEPS.length;

  useEffect(() => {
    setMessage(null);
  }, [step, mode]);

  const handleFile = (e) => {
    const file = e.target.files?.[0];
    if (!file) return;
    if (!/^image\//.test(file.type)) {
      setMessage({ tone: "warning", text: "Please choose an image file (JPG or PNG)." });
      return;
    }
    const reader = new FileReader();
    reader.onload = () => {
      const base64 = String(reader.result).split(",")[1];
      setShots((prev) => ({ ...prev, [current.pose]: base64 }));
      setMessage(null);
    };
    reader.onerror = () => setMessage({ tone: "danger", text: "Could not read that image. Try another file." });
    reader.readAsDataURL(file);
    if (fileRef.current) fileRef.current.value = "";
  };

  const onCaptured = (base64) => {
    setShots((prev) => ({ ...prev, [current.pose]: base64 }));
    setMessage(null);
  };

  const retake = () => {
    setShots((prev) => {
      const next = { ...prev };
      delete next[current.pose];
      return next;
    });
    setMessage(null);
  };

  const goNext = () => {
    if (step < STEPS.length - 1) setStep(step + 1);
  };

  const goBack = () => {
    if (step > 0) setStep(step - 1);
  };

  const handleRegister = async () => {
    if (!allCaptured) {
      setMessage({
        tone: "warning",
        text: `Capture all three angles before registering (${capturedCount}/3 done).`,
      });
      return;
    }
    setBusy(true);
    setMessage(null);
    try {
      const res = await recognitionApi.registerMulti(studentId, name, {
        front: shots.front,
        left: shots.left,
        right: shots.right,
      });
      setDone(res);
      setMessage({
        tone: "success",
        text: `Face registered for ${res.name || name} using ${res.angles} angles.`,
      });
      if (onDone) onDone(res);
    } catch (err) {
      setMessage({
        tone: "danger",
        text: err?.response?.data?.detail || err.message || "Registration failed. Please try again.",
      });
    } finally {
      setBusy(false);
    }
  };

  return (
    <div
      style={{
        position: "fixed",
        inset: 0,
        background: "rgba(0,0,0,0.45)",
        display: "flex",
        alignItems: "center",
        justifyContent: "center",
        zIndex: 1001,
        padding: "1rem",
        overflowY: "auto",
      }}
    >
      <div
        style={{
          background: "#fff",
          borderRadius: "10px",
          width: 480,
          maxWidth: "100%",
          maxHeight: "92vh",
          overflowY: "auto",
          padding: "1.25rem",
          boxShadow: "0 20px 50px rgba(0,0,0,0.3)",
        }}
      >
        <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: "0.75rem" }}>
          <h3 style={{ margin: 0, fontSize: "1rem", color: COLORS.primary }}>
            Register Face — {name || studentId}
          </h3>
          <button onClick={onClose} style={{ background: "transparent", border: "none", cursor: "pointer", fontSize: "1.2rem" }}>✕</button>
        </div>

        {/* progress */}
        <div style={{ display: "flex", gap: "0.4rem", marginBottom: "0.6rem" }}>
          {STEPS.map((s, i) => {
            const state = shots[s.pose] ? "done" : i === step ? "current" : "todo";
            return (
              <div key={s.pose} style={{ flex: 1 }}>
                <div
                  style={{
                    height: "6px",
                    borderRadius: "3px",
                    background:
                      state === "done" ? COLORS.success : state === "current" ? COLORS.primary : "#dee2e6",
                  }}
                />
                <div style={{ fontSize: "0.68rem", color: "#555", marginTop: "0.2rem", textAlign: "center" }}>
                  {i + 1}. {s.label} {state === "done" ? "✓" : ""}
                </div>
              </div>
            );
          })}
        </div>
        <p style={{ fontSize: "0.78rem", color: "#555", margin: "0 0 0.75rem" }}>
          <strong>
            Step {step + 1} of {STEPS.length}: {current.label}
          </strong>
          <br />
          {current.instruction}
        </p>

        {!done && (
          <>
            <div style={{ display: "flex", gap: "0.5rem", marginBottom: "0.75rem" }}>
              <button
                onClick={() => setMode("camera")}
                style={{ ...smallButton(mode === "camera" ? COLORS.primary : "#ced4da"), color: mode === "camera" ? "#fff" : "#333" }}
              >
                📷 Camera
              </button>
              <button
                onClick={() => setMode("upload")}
                style={{ ...smallButton(mode === "upload" ? COLORS.primary : "#ced4da"), color: mode === "upload" ? "#fff" : "#333" }}
              >
                🖼 Upload
              </button>
            </div>

            {mode === "camera" ? (
              <CameraPreview
                key={`${current.pose}-${mode}`}
                facingMode="user"
                showSwitch
                active={!currentShot}
                hint={current.instruction}
                compact
                onImageCaptured={onCaptured}
                onError={(e) => setMessage({ tone: "danger", text: e.message || "Camera error" })}
              />
            ) : (
              <div style={{ border: "1px dashed #ced4da", borderRadius: "8px", padding: "1rem", textAlign: "center", background: "#fafafa" }}>
                <input ref={fileRef} type="file" accept="image/*" onChange={handleFile} style={{ fontSize: "0.8rem", maxWidth: "100%" }} />
                <p style={{ fontSize: "0.72rem", color: "#666", margin: "0.5rem 0 0" }}>
                  Choose one clear photo for the <strong>{current.label}</strong> angle.
                </p>
              </div>
            )}

            {currentShot && (
              <div style={{ marginTop: "0.75rem" }}>
                <img
                  src={`data:image/jpeg;base64,${currentShot}`}
                  alt={`${current.label} preview`}
                  style={{ width: "100%", borderRadius: "8px", maxHeight: 200, objectFit: "cover", display: "block" }}
                />
                <div style={{ display: "flex", gap: "0.5rem", marginTop: "0.4rem", alignItems: "center" }}>
                  <span style={{ fontSize: "0.75rem", color: COLORS.success, fontWeight: 600 }}>✓ {current.label} captured</span>
                  <button onClick={retake} style={{ ...smallButton("#95a5a6"), color: "#fff", marginLeft: "auto" }}>Retake</button>
                </div>
              </div>
            )}

            {message && <Banner tone={message.tone} style={{ marginTop: "0.75rem" }}>{message.text}</Banner>}

            <div style={{ display: "flex", justifyContent: "space-between", gap: "0.5rem", marginTop: "1rem", flexWrap: "wrap" }}>
              <button onClick={goBack} disabled={step === 0} style={{ ...smallButton("#ced4da") }}>← Back</button>
              <div style={{ display: "flex", gap: "0.5rem" }}>
                <button onClick={onClose} style={{ ...smallButton("#95a5a6") }}>Cancel</button>
                {step < STEPS.length - 1 ? (
                  <button onClick={goNext} disabled={isBlank(currentShot)} style={{ ...buttonStyle(COLORS.primary) }}>
                    Next →
                  </button>
                ) : (
                  <button
                    onClick={handleRegister}
                    disabled={busy || !allCaptured}
                    style={{ ...buttonStyle(COLORS.success), opacity: allCaptured ? 1 : 0.6 }}
                  >
                    {busy ? "Registering..." : `Register Face (${capturedCount}/3)`}
                  </button>
                )}
              </div>
            </div>
          </>
        )}

        {done && (
          <div style={{ marginTop: "0.75rem" }}>
            <Banner tone="success">{message?.text}</Banner>
            <div style={{ display: "flex", justifyContent: "space-between", marginTop: "0.25rem", gap: "0.5rem" }}>
              <span style={{ fontSize: "0.78rem", color: "#555" }}>
                Stored angles: {(done.templates || []).map((t) => t.pose).join(", ")}
              </span>
              <button onClick={onClose} style={{ ...buttonStyle(COLORS.primary) }}>Done</button>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}