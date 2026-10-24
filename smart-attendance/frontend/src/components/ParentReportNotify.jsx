import React, { useCallback, useEffect, useRef, useState } from "react";
import { teacherApi } from "../services/api";
import { COLORS, Banner, Table, EmptyRow, buttonStyle, cardStyle, tdStyle, StatusBadge } from "../theme";

const OUTCOME_META = {
  sent: { label: "Sent", color: COLORS.success },
  failed: { label: "Failed", color: COLORS.danger },
  skipped: { label: "Skipped", color: "#95a5a6" },
  unconfigured: { label: "Not configured", color: COLORS.warning },
  duplicate: { label: "Duplicate", color: COLORS.info },
};

const CHANNEL_LABELS = {
  whatsapp: "Parent's WhatsApp",
  email: "Email",
};

const BLOCK_REASON_TEXT = {
  no_parent_contact: "No parent contact on file",
  no_valid_address: "No email address on file",
  unverified_contact: "Parent contact not verified",
  invalid_number: "Invalid phone number",
  invalid_address: "Invalid email address",
};

export default function ParentReportNotify({ mode, onClose }) {
  const [channel, setChannel] = useState("whatsapp");
  const [phase, setPhase] = useState("loading"); // loading | choose | confirm | sending | done
  const [data, setData] = useState(null);
  const [selected, setSelected] = useState(new Set());
  const [error, setError] = useState(null);
  const [batch, setBatch] = useState(null);
  const pollRef = useRef(null);

  const load = useCallback(async (ch) => {
    setPhase("loading");
    setError(null);
    try {
      const d = await teacherApi.notifyEligible(ch);
      setData(d);
      setSelected(new Set());
      setPhase(mode === "all" ? "confirm" : "choose");
    } catch (err) {
      setError(err?.response?.data?.detail || err.message || "Unable to load students");
      setPhase("choose");
    }
  }, [mode]);

  useEffect(() => {
    load(channel);
    return () => clearInterval(pollRef.current);
  }, [load, channel]);

  useEffect(() => () => clearInterval(pollRef.current), []);

  const students = data?.students || [];
  const channelStatus = data?.channels?.[channel];
  const counts = data?.counts;

  const start = async (studentIds) => {
    setError(null);
    setPhase("sending");
    try {
      const res = await teacherApi.notifyStart({ student_ids: studentIds, channel });
      setBatch({ ...res, processed: 0, total: res.total, results: [], summary: {}, status: "running" });
      clearInterval(pollRef.current);
      pollRef.current = setInterval(async () => {
        try {
          const b = await teacherApi.notifyBatch(res.batch_id);
          setBatch(b);
          if (b.status === "done") {
            clearInterval(pollRef.current);
            setPhase("done");
          }
        } catch (err) {
          clearInterval(pollRef.current);
          setError(err?.response?.data?.detail || "Lost contact with the server while sending.");
          setPhase("done");
        }
      }, 700);
    } catch (err) {
      setError(err?.response?.data?.detail || err.message || "Failed to start sending");
      setPhase(mode === "all" ? "confirm" : "choose");
    }
  };

  const close = () => {
    clearInterval(pollRef.current);
    onClose();
  };

  const summary = batch?.summary || {};
  const total = batch?.total || 0;
  const processed = batch?.processed || 0;
  const pct = total ? Math.round((processed / total) * 100) : 0;

  return (
    <div style={overlayStyle} onClick={close}>
      <div style={modalStyle} onClick={(e) => e.stopPropagation()}>
        <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: "0.75rem" }}>
          <h3 style={{ margin: 0 }}>
            {mode === "all" ? "Send report to all parents" : "Select students"}
          </h3>
          <button onClick={close} style={buttonStyle("#95a5a6")}>Close</button>
        </div>

        {error && <Banner tone="danger">{error}</Banner>}

        <div style={{ display: "flex", gap: "0.4rem", marginBottom: "0.8rem", alignItems: "center" }}>
          <span style={{ fontSize: "0.8rem", fontWeight: 600, color: "#475569" }}>Send via</span>
          {Object.keys(CHANNEL_LABELS).map((ch) => (
            <button
              key={ch}
              onClick={() => { setBatch(null); setChannel(ch); }}
              style={{
                ...buttonStyle(channel === ch ? COLORS.primary : "#e2e8f0"),
                color: channel === ch ? "#fff" : "#475569",
                fontSize: "0.78rem",
                padding: "0.35rem 0.7rem",
              }}
            >
              {CHANNEL_LABELS[ch]}
            </button>
          ))}
          {channelStatus && !channelStatus.configured && (
            <span style={{ fontSize: "0.72rem", color: COLORS.warning, fontWeight: 600 }}>
              not configured
            </span>
          )}
        </div>

        {channelStatus && !channelStatus.configured && (
          <Banner tone="warning">
            <strong>{CHANNEL_LABELS[channel]} delivery is not configured on this server.</strong>{" "}
            Nothing will actually be delivered until an operator sets{" "}
            {(channelStatus.missing || []).join(", ")}. Every attempt below is reported truthfully
            as “Not configured” — no message is ever claimed as sent.
          </Banner>
        )}

        {phase === "loading" && <p style={{ color: "#666" }}>Loading your students…</p>}

        {(phase === "choose" || phase === "confirm") && data && (
          <>
            <p style={{ fontSize: "0.85rem", color: "#555", marginTop: 0 }}>
              <strong>{counts.total}</strong> student(s) in your classes ·{" "}
              <strong style={{ color: COLORS.success }}>{counts.deliverable}</strong> with a
              verified {channel === "email" ? "email address" : "parent number"} ·{" "}
              <strong>{counts.no_contact}</strong> with nothing on file ·{" "}
              <strong>{counts.unverified}</strong> unverified
            </p>

            {mode === "all" ? (
              <Banner tone="info">
                This will attempt to send each student's own attendance PDF over{" "}
                <strong>{CHANNEL_LABELS[channel]}</strong> —{" "}
                <strong>{counts.deliverable} recipient(s)</strong> can actually receive it. The rest
                will be reported as skipped.
              </Banner>
            ) : (
              <div style={{ maxHeight: "320px", overflowY: "auto", marginBottom: "0.75rem", border: "1px solid #e2e8f0", borderRadius: "8px" }}>
                <Table head={["", "USN", "Name", "Class", channel === "email" ? "Email" : "Parent WhatsApp", "Via", "Status"]}>
                  {students.map((s) => (
                    <tr key={s.student_id}>
                      <td style={tdStyle}>
                        <input
                          type="checkbox"
                          checked={selected.has(s.student_id)}
                          onChange={(e) => {
                            const next = new Set(selected);
                            if (e.target.checked) next.add(s.student_id);
                            else next.delete(s.student_id);
                            setSelected(next);
                          }}
                        />
                      </td>
                      <td style={tdStyle}>{s.usn}</td>
                      <td style={tdStyle}>{s.name}</td>
                      <td style={tdStyle}>{s.branch} / {s.semester} / {s.section}</td>
                      <td style={tdStyle}>{s.recipient_masked || "—"}</td>
                      <td style={{ ...tdStyle, fontSize: "0.75rem", color: "#64748b" }}>
                        {s.deliverable
                          ? (s.recipient_via === "parent" ? "Parent" : "Student")
                          : "—"}
                      </td>
                      <td style={tdStyle}>
                        {s.deliverable
                          ? <StatusBadge status="present" />
                          : <span style={{ fontSize: "0.75rem", color: "#95a5a6" }}>
                              {BLOCK_REASON_TEXT[s.block_reason] || s.block_reason?.replace(/_/g, " ")}
                            </span>}
                      </td>
                    </tr>
                  ))}
                  {students.length === 0 && <EmptyRow colSpan={7}>No students in your assigned classes.</EmptyRow>}
                </Table>
              </div>
            )}

            <div style={{ display: "flex", gap: "0.5rem", flexWrap: "wrap" }}>
              <button onClick={() => load(channel)} style={buttonStyle("#95a5a6")}>Refresh</button>
              {mode === "select" ? (
                <button
                  onClick={() => start([...selected])}
                  disabled={selected.size === 0}
                  style={{ ...buttonStyle(COLORS.primary), opacity: selected.size === 0 ? 0.5 : 1 }}
                >
                  Send to {selected.size} selected via {CHANNEL_LABELS[channel]}
                </button>
              ) : (
                <button onClick={() => start([])} style={buttonStyle(COLORS.primary)}>
                  Send to all {counts.total} via {CHANNEL_LABELS[channel]}
                </button>
              )}
            </div>
          </>
        )}

        {phase === "sending" && batch && (
          <div>
            <p style={{ fontSize: "0.9rem", marginTop: 0 }}>
              Sending via {CHANNEL_LABELS[batch.channel || channel]}… {processed} of {total}
            </p>
            <div style={{ background: "#e2e8f0", borderRadius: "8px", height: "10px", overflow: "hidden" }}>
              <div style={{ width: `${pct}%`, background: COLORS.primary, height: "100%", transition: "width .3s" }} />
            </div>
          </div>
        )}

        {phase === "done" && batch && (
          <>
            <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fit, minmax(110px, 1fr))", gap: "0.6rem", margin: "0.75rem 0" }}>
              {["sent", "failed", "skipped", "unconfigured", "duplicate"].map((k) => (
                <div key={k} style={{ ...cardStyle, borderTop: `3px solid ${OUTCOME_META[k].color}`, textAlign: "center", padding: "0.7rem" }}>
                  <div style={{ fontSize: "1.4rem", fontWeight: 700, color: OUTCOME_META[k].color }}>{summary[k] ?? 0}</div>
                  <div style={{ fontSize: "0.72rem", color: "#475569" }}>{OUTCOME_META[k].label}</div>
                </div>
              ))}
            </div>
            {batch.error && <Banner tone="danger">{batch.error}</Banner>}
            <div style={{ maxHeight: "240px", overflowY: "auto", border: "1px solid #e2e8f0", borderRadius: "8px" }}>
              <Table head={["USN", "Name", "Recipient", "Via", "Result", "Detail"]}>
                {batch.results.map((r) => (
                  <tr key={r.student_id}>
                    <td style={tdStyle}>{r.usn}</td>
                    <td style={tdStyle}>{r.name}</td>
                    <td style={tdStyle}>{r.recipient_masked || "—"}</td>
                    <td style={{ ...tdStyle, fontSize: "0.75rem", color: "#64748b" }}>
                      {r.recipient_via === "parent" ? "Parent" : r.recipient_via === "student" ? "Student" : "—"}
                    </td>
                    <td style={{ ...tdStyle, color: OUTCOME_META[r.outcome]?.color, fontWeight: 600 }}>
                      {OUTCOME_META[r.outcome]?.label || r.outcome}
                    </td>
                    <td style={{ ...tdStyle, fontSize: "0.78rem", color: "#666" }}>{r.detail}</td>
                  </tr>
                ))}
                {batch.results.length === 0 && <EmptyRow colSpan={6}>Nothing was processed.</EmptyRow>}
              </Table>
            </div>
          </>
        )}
      </div>
    </div>
  );
}

const overlayStyle = {
  position: "fixed", inset: 0, background: "rgba(15,23,42,0.55)",
  display: "flex", alignItems: "center", justifyContent: "center",
  zIndex: 1000, padding: "1rem",
};
const modalStyle = {
  background: "#fff", borderRadius: "12px", padding: "1.25rem",
  width: "min(920px, 100%)", maxHeight: "90vh", overflowY: "auto",
  boxShadow: "0 20px 40px rgba(0,0,0,0.25)",
};
