import { ImageResponse } from "next/og";

export const runtime = "nodejs";

export const alt = "Vantis — Reliability infrastructure for automation";
export const size = {
  width: 1200,
  height: 630,
};
export const contentType = "image/png";

export default async function Image() {
  return new ImageResponse(
    (
      <div
        style={{
          width: "100%",
          height: "100%",
          display: "flex",
          flexDirection: "column",
          alignItems: "flex-start",
          justifyContent: "space-between",
          backgroundColor: "#070709",
          padding: "80px",
          position: "relative",
        }}
      >
        {/* Subtle glow effect */}
        <div
          style={{
            position: "absolute",
            top: "-120px",
            right: "-80px",
            width: "550px",
            height: "550px",
            borderRadius: "50%",
            background:
              "radial-gradient(circle, rgba(139, 92, 246, 0.28) 0%, rgba(7, 7, 9, 0) 70%)",
          }}
        />

        {/* Brand header */}
        <div style={{ display: "flex", alignItems: "center", gap: "16px" }}>
          <div
            style={{
              width: "48px",
              height: "48px",
              borderRadius: "12px",
              background: "linear-gradient(135deg, #a78bfa, #7c3aed)",
              display: "flex",
              alignItems: "center",
              justifyContent: "center",
              color: "#ffffff",
              fontSize: "26px",
              fontWeight: 800,
            }}
          >
            V
          </div>
          <span
            style={{
              fontSize: "30px",
              fontWeight: 700,
              color: "#ffffff",
              letterSpacing: "-0.5px",
            }}
          >
            Vantis
          </span>
        </div>

        {/* Value proposition */}
        <div
          style={{
            display: "flex",
            flexDirection: "column",
            gap: "20px",
            maxWidth: "1000px",
          }}
        >
          <div
            style={{
              fontSize: "56px",
              fontWeight: 800,
              color: "#ffffff",
              lineHeight: 1.15,
              letterSpacing: "-1.5px",
            }}
          >
            Reliability infrastructure for automation.
          </div>
          <div
            style={{
              fontSize: "24px",
              color: "#a1a1aa",
              lineHeight: 1.4,
            }}
          >
            Monitor workflows, detect anomalies in real time, and turn failures
            into fixes across n8n, Zapier, Temporal, and custom APIs.
          </div>
        </div>

        {/* Footer badges */}
        <div
          style={{
            display: "flex",
            alignItems: "center",
            justifyContent: "space-between",
            width: "100%",
          }}
        >
          <div
            style={{
              backgroundColor: "rgba(255, 255, 255, 0.08)",
              border: "1px solid rgba(255, 255, 255, 0.16)",
              borderRadius: "9999px",
              padding: "10px 24px",
              color: "#e4e4e7",
              fontSize: "18px",
              fontWeight: 500,
            }}
          >
            vantis.run
          </div>
          <div
            style={{
              display: "flex",
              gap: "20px",
              color: "#71717a",
              fontSize: "18px",
            }}
          >
            <span>Build</span>
            <span>·</span>
            <span>Monitor</span>
            <span>·</span>
            <span>Detect</span>
            <span>·</span>
            <span>Diagnose</span>
          </div>
        </div>
      </div>
    ),
    {
      ...size,
    }
  );
}
