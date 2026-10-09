"use client";

export function LandingStyles() {
  return (
    <>
      <style jsx global>
        {`
        :root {
          --flowops-black: #050505;
          --flowops-surface: #0a0a0a;
          --flowops-border: rgba(255, 255, 255, 0.075);
          --flowops-border-strong: rgba(255, 255, 255, 0.12);
          --flowops-text: #f5f5f5;
          --flowops-muted: #8d8d8d;
        }

        html.light,
        html:not(.dark) {
          --flowops-black: #f8fafc;
          --flowops-surface: #ffffff;
          --flowops-border: rgba(0, 0, 0, 0.08);
          --flowops-border-strong: rgba(0, 0, 0, 0.16);
          --flowops-text: #0f172a;
          --flowops-muted: #64748b;
        }

        html {
          scroll-behavior: smooth;
        }

        body {
          margin: 0;
          color: var(--flowops-text);
        }

        .dark body {
          background: var(--flowops-black) !important;
        }

        html.light body,
        html:not(.dark) body {
          background: #f8fafc !important;
          color: #0f172a !important;
        }

        .dark main {
          min-height: 100vh;
          overflow-x: clip;
          background:
            radial-gradient(circle at 50% -10%, rgba(255,255,255,0.035), transparent 34%),
            #050505 !important;
          color: var(--flowops-text);
        }

        html.light main,
        html:not(.dark) main {
          min-height: 100vh;
          overflow-x: clip;
          background:
            radial-gradient(circle at 50% -10%, rgba(0,0,0,0.02), transparent 34%),
            #f8fafc !important;
          color: #0f172a !important;
        }

        /* ---- Premium glass announcement bar ---- */
        .announcement {
          background: rgba(255, 255, 255, 0.025) !important;
          border-bottom: 1px solid rgba(255,255,255,0.055);
          color: rgba(255,255,255,0.72) !important;
          backdrop-filter: blur(18px);
          -webkit-backdrop-filter: blur(18px);
        }


        /* ---- Translucent floating navbar ---- */
        header {
          position: sticky !important;
          top: 0;
          z-index: 100;
          margin: 0 auto;
          background: rgba(5, 5, 5, 0.38) !important;
          border-bottom: 1px solid rgba(255,255,255,0.055) !important;
          backdrop-filter: blur(22px) saturate(115%);
          -webkit-backdrop-filter: blur(22px) saturate(115%);
          box-shadow: 0 8px 32px rgba(0,0,0,0.16);
          transition:
            background 220ms ease,
            box-shadow 220ms ease,
            border-color 220ms ease;
        }

        header.is-scrolled {
          background: rgba(5, 5, 5, 0.72) !important;
          border-bottom-color: rgba(255,255,255,0.09) !important;
          box-shadow: 0 10px 40px rgba(0,0,0,0.32);
        }

        header nav a {
          color: rgba(255,255,255,0.62) !important;
          transition: color 180ms ease;
        }

        header nav a:hover {
          color: rgba(255,255,255,0.96) !important;
        }

        /* ---- Glass buttons ---- */
        .white-button {
          background: rgba(255,255,255,0.90) !important;
          color: #050505 !important;
          border: 1px solid rgba(255,255,255,0.18) !important;
          border-radius: 7px !important;
          box-shadow:
            inset 0 1px 0 rgba(255,255,255,0.55),
            0 8px 24px rgba(0,0,0,0.18);
          backdrop-filter: blur(12px);
          -webkit-backdrop-filter: blur(12px);
          transition:
            transform 180ms ease,
            background 180ms ease,
            box-shadow 180ms ease;
        }

        .white-button {
          min-height: 42px;
          padding: 0 18px !important;
          font-size: 14px !important;
          font-weight: 600 !important;
          letter-spacing: -0.01em;
        }

        .white-button:hover {
          background: rgba(255,255,255,0.98) !important;
          transform: translateY(-1px);
          box-shadow:
            inset 0 1px 0 rgba(255,255,255,0.7),
            0 12px 30px rgba(0,0,0,0.28);
        }

        .outline-button {
          background: rgba(255,255,255,0.035) !important;
          color: rgba(255,255,255,0.78) !important;
          border: 1px solid rgba(255,255,255,0.13) !important;
          border-radius: 7px !important;
          backdrop-filter: blur(14px);
          -webkit-backdrop-filter: blur(14px);
          box-shadow: inset 0 1px 0 rgba(255,255,255,0.035);
          transition:
            transform 180ms ease,
            background 180ms ease,
            border-color 180ms ease,
            color 180ms ease;
        }

        .outline-button {
          min-height: 42px;
          padding: 0 18px !important;
          font-size: 14px !important;
          font-weight: 500 !important;
        }

        .outline-button:hover {
          background: rgba(255,255,255,0.075) !important;
          border-color: rgba(255,255,255,0.20) !important;
          color: #fff !important;
          transform: translateY(-1px);
        }

        /* ---- Black cinematic hero ---- */
        .hero {
          position: relative;
          padding: clamp(92px, 9vw, 140px) clamp(24px, 6vw, 96px) clamp(110px, 10vw, 160px) !important;
          background: #050505 !important;
          isolation: isolate;
        }

        .hero > .reveal-fade,
        .hero > .hero-window-wrap {
          position: relative;
          z-index: 1;
        }

        .hero > .hero-window-wrap {
          width: min(1240px, 100%) !important;
          margin-inline: auto !important;
        }

        .hero-atmosphere {
          position: absolute !important;
          top: 18px !important;
          right: 28px !important;
          bottom: 18px !important;
          left: 28px !important;
          z-index: 0 !important;
          overflow: hidden !important;
          border-radius: 7px !important;
          background: #050505 !important;
          pointer-events: none !important;
        }

        .hero-atmosphere::before {
          content: "";
          position: absolute;
          inset: 0;
          background-image: url("https://images.unsplash.com/photo-1464822759023-fed622ff2c3b?auto=format&fit=crop&w=2400&q=88");
          background-size: cover;
          background-position: center 42%;
          background-repeat: no-repeat;
          opacity: 0.34;
          filter: none;
        }

        .hero-atmosphere::after {
          content: "";
          display: block !important;
          position: absolute;
          inset: 0;
          pointer-events: none;
          background: linear-gradient(
            to bottom,
            rgba(5,5,5,0) 48%,
            rgba(5,5,5,0.10) 62%,
            rgba(5,5,5,0.42) 76%,
            rgba(5,5,5,0.78) 89%,
            #050505 100%
          );
        }

        .hero-atmosphere .orb {
          display: none !important;
        }

        .hero-atmosphere .grid-lines {
          opacity: 0.16 !important;
          background-size: 72px 72px !important;
          background-image:
            linear-gradient(rgba(255,255,255,0.020) 1px, transparent 1px),
            linear-gradient(90deg, rgba(255,255,255,0.020) 1px, transparent 1px) !important;
          pointer-events: none;
        }

        .hero-atmosphere .grain {
          opacity: 0.025 !important;
        }

        .hero h1 {
          letter-spacing: -0.045em !important;
          line-height: 0.98 !important;
          text-wrap: balance;
        }
        .hero .buttons {
          display: flex;
          align-items: center;
          justify-content: center;
          gap: 12px !important;
          margin-top: 28px !important;
        }

        .hero .buttons + small,
        .hero small {
          display: block;
          margin-top: 16px !important;
        }

        .hero .tabs {
          margin-top: 42px !important;
        }

        .hero .hero-window-wrap {
          margin-top: 34px !important;
        }

        .hero > .reveal-fade > p {
          margin-top: 24px !important;
        }

        .hero > .reveal-fade > .eyebrow {
          margin-bottom: 22px !important;
        }

        .hero .white-button,
        .hero .outline-button {
          display: inline-flex;
          align-items: center;
          justify-content: center;
          gap: 8px;
        }


        .hero > p,
        .hero .reveal-fade > p {
          color: rgba(255,255,255,0.60) !important;
        }

        /* ---- Consistent editorial section padding ---- */
        .section,
        .wide-panel,
        .center-section,
        .faq-section,
        .carousel,
        .cta {
          width: min(1400px, calc(100% - 128px)) !important;
          margin-inline: auto !important;
        }

        .section {
          padding-top: clamp(110px, 11vw, 175px) !important;
          padding-bottom: clamp(110px, 11vw, 175px) !important;
        }

        .center-section {
          padding-top: clamp(110px, 10vw, 160px) !important;
          padding-bottom: clamp(110px, 10vw, 160px) !important;
        }

        .wide-panel {
          margin-top: 20px !important;
          margin-bottom: 20px !important;
          padding: clamp(72px, 7vw, 110px) !important;
          border: 1px solid var(--flowops-border) !important;
          border-radius: 26px !important;
          background: #070707 !important;
          overflow: hidden;
        }

        .faq-section {
          padding-top: 120px !important;
          padding-bottom: 130px !important;
        }

        /* Center only the requested section headings. */
        .messy-middle-intro h2,
        .faq-section > .reveal:first-child h2 {
          width: 100%;
          text-align: center !important;
          margin-left: auto !important;
          margin-right: auto !important;
        }

        .faq-section > .reveal:first-child {
          text-align: center;
        }

        .faq-section > .reveal:first-child .eyebrow {
          justify-content: center;
        }


        .hero .panel-fade-in {
          position: relative;
          width: 100%;
          min-height: 1px;
        }

        .hero .panel-fade-in > * {
          width: 100%;
        }

        .hero .tabs {
          position: relative;
          z-index: 3;
        }

        .hero .tab-indicator {
          transition: left 220ms cubic-bezier(.16,1,.3,1),
                      width 220ms cubic-bezier(.16,1,.3,1);
        }

        /* ---- Cinematic product stages ---- */
        .window-stage {
          position: relative;
          isolation: isolate;
          min-height: 100%;
          padding: 0 !important;
          border: 1px solid rgba(255,255,255,0.10);
          border-radius: 18px;
          overflow: hidden;
          background-color: #0a0a0a;
          background-size: cover;
          background-position: center;
          box-shadow:
            0 30px 90px rgba(0,0,0,0.36),
            inset 0 1px 0 rgba(255,255,255,0.06);
        }

        .window-stage::before {
          content: "";
          position: absolute;
          inset: 0;
          z-index: -1;
          background:
            linear-gradient(180deg, rgba(0,0,0,0.18), rgba(0,0,0,0.62)),
            radial-gradient(circle at 50% 20%, rgba(255,255,255,0.02), rgba(0,0,0,0.30));
        }

        .window-stage[style] {
          padding: clamp(18px, 2.2vw, 34px) !important;
          border-radius: 22px;
          background-blend-mode: normal;
        }

        .window-stage[style] .window {
          filter: none !important;
        }

        .hero-window-wrap .window-stage {
          border: 0 !important;
          background: transparent !important;
          box-shadow: none !important;
        }

        .hero-window-wrap .window-stage::before,
        .hero-window-wrap .window-stage::after {
          display: none;
        }

        .window-stage::after {
          content: "";
          position: absolute;
          inset: 0;
          z-index: -1;
          box-shadow: inset 0 0 90px rgba(0,0,0,0.42);
          pointer-events: none;
        }

        .window-stage .window {
          position: relative;
          z-index: 1;
          width: 100%;
          margin-inline: auto;
          overflow: hidden;
          border: 1px solid rgba(255,255,255,0.10) !important;
          border-radius: 16px !important;
          background: rgba(5,5,5,0.88) !important;
          box-shadow:
            0 24px 70px rgba(0,0,0,0.46),
            inset 0 1px 0 rgba(255,255,255,0.035);
          backdrop-filter: blur(10px);
          -webkit-backdrop-filter: blur(10px);
        }

        .window-bar {
          background: rgba(12,12,12,0.88) !important;
          border-bottom: 1px solid rgba(255,255,255,0.075) !important;
        }

        /* Product windows should feel large, not like small dashboard cards. */
        .hero-window-wrap,
        .reveal-right .window-stage,
        .reveal-left .window-stage {
          width: 100%;
        }

        /* ---- Neutralize old green atmospheric treatments ---- */
        .wide-atmosphere,
        .cta-atmosphere {
          opacity: 0.16 !important;
        }

        .wide-atmosphere .orb,
        .cta-atmosphere .orb {
          filter: grayscale(1) !important;
        }

        /* ---- Pills ---- */
        .pills span,
        .pill-tag {
          background: rgba(255,255,255,0.045) !important;
          border: 1px solid rgba(255,255,255,0.075) !important;
          color: rgba(255,255,255,0.60) !important;
          backdrop-filter: blur(10px);
          -webkit-backdrop-filter: blur(10px);
        }

        /* ---- Fine structural lines ---- */
        .section,
        .center-section,
        .faq-section,
        footer {
          border-color: rgba(255,255,255,0.065) !important;
        }


        /* ==============================================================
           COMPONENT MOTION — energetic, but contained to product UI
           ============================================================== */

        .window-stage {
          transform: translateZ(0);
        }

        .window-stage .window {
          animation: flowops-window-float 7s cubic-bezier(.45,.05,.55,.95) infinite;
          transform-origin: 50% 60%;
        }

        .hero-window-wrap .window {
          animation:
            flowops-window-float 7s cubic-bezier(.45,.05,.55,.95) infinite,
            flowops-window-breathe 4.5s ease-in-out infinite;
          backdrop-filter: none !important;
          -webkit-backdrop-filter: none !important;
          background: #080808 !important;
        }

        /* Monitor / hero product: solid UI surface, no glassmorphism. */
        .hero-window-wrap .window-stage {
          backdrop-filter: none !important;
          -webkit-backdrop-filter: none !important;
        }

        .hero-window-wrap .hero-window,
        .hero-window-wrap .dashboard,
        .hero-window-wrap .window-heading {
          backdrop-filter: none !important;
          -webkit-backdrop-filter: none !important;
        }

        .window-bar span {
          animation: flowops-bar-glow 3.5s ease-in-out infinite;
        }

        .metric-grid > div {
          animation: flowops-metric-rise 700ms cubic-bezier(.16,1,.3,1) both;
        }

        .metric-grid > div:nth-child(2) {
          animation-delay: 100ms;
        }

        .metric-grid > div:nth-child(3) {
          animation-delay: 200ms;
        }

        .trace-line {
          animation: flowops-trace-slide 650ms cubic-bezier(.16,1,.3,1) both;
        }

        .trace-line:nth-child(2) { animation-delay: 80ms; }
        .trace-line:nth-child(3) { animation-delay: 160ms; }
        .trace-line:nth-child(4) { animation-delay: 240ms; }

        .trace-line b,
        .status b,
        .pulse {
          animation: flowops-status-pulse 1.8s ease-in-out infinite;
        }

        .anomaly-chart {
          position: relative;
          overflow: hidden;
        }

        .anomaly-chart::after {
          content: "";
          position: absolute;
          top: 0;
          bottom: 0;
          left: -18%;
          width: 18%;
          background: linear-gradient(
            90deg,
            transparent,
            rgba(255,255,255,0.14),
            transparent
          );
          transform: skewX(-18deg);
          animation: flowops-scan 3.2s ease-in-out infinite;
          pointer-events: none;
        }

        .bar-col {
          transform-origin: 50% 100%;
          animation: flowops-bar-pop 800ms cubic-bezier(.16,1,.3,1) both;
        }

        .bar-col:nth-child(2) { animation-delay: 50ms; }
        .bar-col:nth-child(3) { animation-delay: 100ms; }
        .bar-col:nth-child(4) { animation-delay: 150ms; }
        .bar-col:nth-child(5) { animation-delay: 200ms; }
        .bar-col:nth-child(6) { animation-delay: 250ms; }
        .bar-col:nth-child(7) { animation-delay: 300ms; }
        .bar-col:nth-child(8) { animation-delay: 350ms; }
        .bar-col:nth-child(9) { animation-delay: 400ms; }
        .bar-col:nth-child(10) { animation-delay: 450ms; }
        .bar-col:nth-child(11) { animation-delay: 500ms; }
        .bar-col:nth-child(12) { animation-delay: 550ms; }

        .alert-row {
          animation: flowops-alert-enter 850ms cubic-bezier(.16,1,.3,1) both;
          animation-delay: 500ms;
        }

        .node-wrap {
          animation: flowops-node-enter 900ms cubic-bezier(.16,1,.3,1) both;
        }

        .node-wrap:nth-child(2) { animation-delay: 120ms; }
        .node-wrap:nth-child(3) { animation-delay: 240ms; }

        .node-card {
          transition:
            transform 220ms cubic-bezier(.16,1,.3,1),
            border-color 220ms ease,
            box-shadow 220ms ease;
        }

        .node-card:hover {
          transform: translateY(-5px) scale(1.025);
          box-shadow:
            0 18px 50px rgba(0,0,0,0.30),
            0 0 28px rgba(255,255,255,0.05);
        }

        .code-card pre {
          animation: flowops-code-focus 1.2s cubic-bezier(.16,1,.3,1) both;
        }

        .blink-cursor {
          animation: flowops-cursor 900ms steps(2,end) infinite;
        }

        .chart-card .chart {
          animation: flowops-chart-reveal 1.1s cubic-bezier(.16,1,.3,1) both;
          transform-origin: center bottom;
        }

        .window-stage:hover .window {
          animation-play-state: paused;
          transform: translateY(-4px) scale(1.008);
          transition: transform 500ms cubic-bezier(.16,1,.3,1);
        }

        @keyframes flowops-window-float {
          0%, 100% { transform: translate3d(0,0,0) rotateX(0deg) rotateY(0deg); }
          50% { transform: translate3d(0,-7px,0) rotateX(.35deg) rotateY(-.25deg); }
        }

        @keyframes flowops-window-breathe {
          0%, 100% { box-shadow: 0 24px 70px rgba(0,0,0,0.46), inset 0 1px 0 rgba(255,255,255,0.035); }
          50% { box-shadow: 0 34px 95px rgba(0,0,0,0.58), inset 0 1px 0 rgba(255,255,255,0.065); }
        }

        @keyframes flowops-bar-glow {
          0%, 100% { opacity: .45; transform: translateX(0); }
          50% { opacity: .9; transform: translateX(3px); }
        }

        @keyframes flowops-metric-rise {
          from { opacity: 0; transform: translateY(18px) scale(.97); }
          to { opacity: 1; transform: translateY(0) scale(1); }
        }

        @keyframes flowops-trace-slide {
          from { opacity: 0; transform: translateX(-18px); }
          to { opacity: 1; transform: translateX(0); }
        }

        @keyframes flowops-status-pulse {
          0%, 100% { transform: scale(1); opacity: .65; box-shadow: 0 0 0 0 rgba(255,255,255,0); }
          50% { transform: scale(1.18); opacity: 1; box-shadow: 0 0 0 5px rgba(255,255,255,.035); }
        }

        @keyframes flowops-scan {
          0% { left: -22%; opacity: 0; }
          15% { opacity: 1; }
          55% { opacity: 1; }
          75%, 100% { left: 115%; opacity: 0; }
        }

        @keyframes flowops-bar-pop {
          from { opacity: 0; transform: scaleY(0); }
          to { opacity: 1; transform: scaleY(1); }
        }

        @keyframes flowops-alert-enter {
          from { opacity: 0; transform: translateY(14px); }
          to { opacity: 1; transform: translateY(0); }
        }

        @keyframes flowops-node-enter {
          from { opacity: 0; transform: translateX(35px) scale(.92); }
          to { opacity: 1; transform: translateX(0) scale(1); }
        }

        @keyframes flowops-code-focus {
          from { opacity: 0; transform: translateY(12px); filter: blur(3px); }
          to { opacity: 1; transform: translateY(0); filter: blur(0); }
        }

        @keyframes flowops-cursor {
          0%, 45% { opacity: 1; }
          46%, 100% { opacity: 0; }
        }

        @keyframes flowops-chart-reveal {
          from { opacity: 0; transform: scaleY(.55) translateY(20px); }
          to { opacity: 1; transform: scaleY(1) translateY(0); }
        }

        @media (prefers-reduced-motion: reduce) {
          .window-stage .window,
          .hero-window-wrap .window,
          .window-bar span,
          .metric-grid > div,
          .trace-line,
          .trace-line b,
          .status b,
          .pulse,
          .anomaly-chart::after,
          .bar-col,
          .alert-row,
          .node-wrap,
          .code-card pre,
          .blink-cursor,
          .chart-card .chart {
            animation: none !important;
            transition: none !important;
          }
        }



        /* ==============================================================
           Hero background invariant
           Monitor / Detect / Diagnose changes only the product panel.
           The background image stays fixed, in the same position.
           ============================================================== */
        .hero-atmosphere,
        .hero-atmosphere::before,
        .hero-atmosphere::after {
          pointer-events: none !important;
          transform: none !important;
        }

        .hero-atmosphere::before {
          background-attachment: fixed !important;
        }

        .hero-atmosphere[data-tab],
        .hero-atmosphere[data-active-tab],
        .hero-atmosphere.monitor,
        .hero-atmosphere.detect,
        .hero-atmosphere.diagnose {
          background-image: inherit !important;
        }

        /* ==============================================================
           Tab behavior — product panel only
           ============================================================== */
        .hero .tabs ~ .hero-window-wrap {
          position: relative !important;
          z-index: 2 !important;
        }

        .hero .hero-atmosphere {
          transition: none !important;
        }

        /* ==============================================================
           Monitor section — solid Vantis product surface (no glass)
           ============================================================== */
        #platform .window-stage {
          background: #090909 !important;
          background-image: none !important;
          padding: 0 !important;
          border-radius: 18px !important;
          box-shadow: 0 28px 80px rgba(0,0,0,0.42) !important;
        }

        #platform .window-stage::before,
        #platform .window-stage::after {
          display: none !important;
        }

        #platform .window {
          background: #090909 !important;
          backdrop-filter: none !important;
          -webkit-backdrop-filter: none !important;
          box-shadow: none !important;
          border-color: rgba(255,255,255,0.08) !important;
        }

        #platform .window-bar {
          background: #0d0d0d !important;
          backdrop-filter: none !important;
          -webkit-backdrop-filter: none !important;
        }

        .flowops-monitor-panel {
          padding: 28px;
          background:
            radial-gradient(circle at 100% 0%, rgba(255,255,255,0.035), transparent 28%),
            #090909;
          min-height: 520px;
        }

        .monitor-top,
        .monitor-score-row,
        .workflow-row,
        .execution-strip,
        .strip-label {
          display: flex;
          align-items: center;
          justify-content: space-between;
        }

        .monitor-top {
          padding-bottom: 24px;
          border-bottom: 1px solid rgba(255,255,255,0.08);
        }

        .monitor-top h3 {
          margin: 6px 0 0;
          font-size: 24px;
          letter-spacing: -0.03em;
        }

        .live-status {
          display: inline-flex;
          align-items: center;
          gap: 8px;
          color: rgba(255,255,255,0.72);
          font-size: 12px;
          padding: 8px 10px;
          border: 1px solid rgba(255,255,255,0.08);
          border-radius: 6px;
          background: #0d0d0d;
        }

        .live-status i {
          width: 7px;
          height: 7px;
          border-radius: 50%;
          background: #22c55e;
          box-shadow: 0 0 12px rgba(34,197,94,0.55);
        }

        .monitor-score-row {
          gap: 16px;
          padding: 24px 0;
        }

        .reliability-score,
        .run-summary {
          flex: 1;
          padding: 18px;
          border: 1px solid rgba(255,255,255,0.08);
          border-radius: 10px;
          background: #0c0c0c;
        }

        .reliability-score span,
        .run-summary span,
        .strip-label span {
          display: block;
          color: rgba(255,255,255,0.52);
          font-size: 12px;
        }

        .reliability-score strong,
        .run-summary strong {
          display: block;
          margin-top: 8px;
          font-size: 30px;
          line-height: 1;
          letter-spacing: -0.04em;
        }

        .reliability-score small,
        .run-summary small {
          display: block;
          margin-top: 8px;
          color: rgba(255,255,255,0.42);
          font-size: 11px;
        }

        .workflow-list {
          border: 1px solid rgba(255,255,255,0.08);
          border-radius: 10px;
          overflow: hidden;
          background: #0b0b0b;
        }

        .workflow-row {
          padding: 16px 18px;
          border-bottom: 1px solid rgba(255,255,255,0.07);
          transition: background 180ms ease;
        }

        .workflow-row:last-child {
          border-bottom: 0;
        }

        .workflow-row:hover {
          background: rgba(255,255,255,0.025);
        }

        .workflow-name {
          display: flex;
          align-items: center;
          gap: 12px;
        }

        .workflow-icon {
          width: 34px;
          height: 34px;
          display: grid;
          place-items: center;
          border-radius: 8px;
          border: 1px solid rgba(255,255,255,0.08);
          color: rgba(255,255,255,0.78);
          background: #111;
        }

        .workflow-name strong {
          display: block;
          font-size: 13px;
        }

        .workflow-name small {
          display: block;
          margin-top: 3px;
          color: rgba(255,255,255,0.42);
          font-size: 11px;
        }

        .workflow-meta {
          display: flex;
          align-items: flex-end;
          flex-direction: column;
          gap: 6px;
          font-size: 11px;
          color: rgba(255,255,255,0.44);
        }

        .workflow-meta b {
          color: #d8d8d8;
          font-weight: 500;
        }

        .workflow-meta .degraded {
          color: #f59e0b;
        }

        .execution-strip {
          margin-top: 18px;
          padding: 16px 0 0;
          border-top: 1px solid rgba(255,255,255,0.08);
        }

        .strip-label {
          align-items: flex-start;
          flex-direction: column;
          gap: 4px;
          min-width: 140px;
        }

        .strip-label small {
          color: rgba(255,255,255,0.38);
          font-size: 11px;
        }

        .activity-bars {
          flex: 1;
          height: 58px;
          display: flex;
          align-items: end;
          justify-content: flex-end;
          gap: 5px;
        }

        .activity-bars i {
          width: 5px;
          min-height: 10px;
          border-radius: 2px 2px 0 0;
          background: linear-gradient(to top, rgba(255,255,255,0.22), rgba(255,255,255,0.86));
          animation: flowops-activity 2.8s ease-in-out infinite;
        }

        .activity-bars i:nth-child(2n) { animation-delay: .15s; }
        .activity-bars i:nth-child(3n) { animation-delay: .35s; }
        .activity-bars i:nth-child(5n) { animation-delay: .55s; }

        @keyframes flowops-activity {
          0%,100% { transform: scaleY(.75); opacity: .55; }
          50% { transform: scaleY(1.08); opacity: 1; }
        }

        @media (max-width: 700px) {
          .flowops-monitor-panel {
            padding: 18px;
            min-height: auto;
          }

          .monitor-score-row {
            flex-direction: column;
          }

          .reliability-score,
          .run-summary {
            width: 100%;
          }

          .workflow-meta {
            display: none;
          }

          .activity-bars {
            gap: 3px;
          }
        }



        /* ==============================================================
           Diagnose / Vantis incident intelligence component
           ============================================================== */
        .flowops-diagnosis {
          position: relative;
          min-height: 500px;
          padding: 24px;
          overflow: hidden;
          background: #080808;
          border: 1px solid rgba(255,255,255,.08);
          border-radius: 7px;
        }

        .flowops-diagnosis::before {
          content: "";
          position: absolute;
          inset: 0;
          background:
            linear-gradient(rgba(255,255,255,.022) 1px, transparent 1px),
            linear-gradient(90deg, rgba(255,255,255,.022) 1px, transparent 1px);
          background-size: 30px 30px;
          mask-image: linear-gradient(to bottom, rgba(0,0,0,.85), transparent 90%);
          pointer-events: none;
        }

        .flowops-diagnosis::after {
          content: "";
          position: absolute;
          width: 320px;
          height: 320px;
          right: -160px;
          top: 90px;
          border-radius: 50%;
          background: radial-gradient(circle, rgba(245,158,11,.07), transparent 68%);
          pointer-events: none;
        }

        .flowops-diagnosis > * {
          position: relative;
          z-index: 1;
        }

        .fd-top {
          display: flex;
          align-items: flex-start;
          justify-content: space-between;
          padding-bottom: 20px;
          border-bottom: 1px solid rgba(255,255,255,.07);
        }

        .fd-top h2 {
          margin: 6px 0 0;
          font-size: 19px;
          letter-spacing: -.035em;
        }

        .fd-live {
          display: inline-flex;
          align-items: center;
          gap: 7px;
          padding: 7px 9px;
          border: 1px solid rgba(245,158,11,.2);
          border-radius: 6px;
          color: #eab65c;
          background: rgba(245,158,11,.04);
          font-size: 9px;
        }

        .fd-live i,
        .fd-bottom i {
          width: 6px;
          height: 6px;
          display: inline-block;
          border-radius: 50%;
          background: #f59e0b;
        }

        .fd-live i {
          box-shadow: 0 0 10px rgba(245,158,11,.7);
          animation: fd-pulse 1.2s ease-in-out infinite;
        }

        .fd-stats {
          display: grid;
          grid-template-columns: repeat(3, 1fr);
          gap: 8px;
          margin: 18px 0;
        }

        .fd-stats > div {
          padding: 13px;
          border: 1px solid rgba(255,255,255,.07);
          border-radius: 6px;
          background: #0c0c0c;
        }

        .fd-stats span,
        .fd-stats small {
          display: block;
          color: rgba(255,255,255,.38);
          font-size: 8px;
        }

        .fd-stats strong {
          display: block;
          margin: 5px 0 3px;
          font-size: 21px;
          letter-spacing: -.04em;
        }

        .fd-map {
          position: relative;
          height: 190px;
          padding: 30px 12px 12px;
          border: 1px solid rgba(255,255,255,.07);
          border-radius: 7px;
          background: #0a0a0a;
          overflow: hidden;
        }

        .fd-map-label {
          position: absolute;
          top: 10px;
          left: 12px;
          color: rgba(255,255,255,.32);
          font-size: 8px;
          text-transform: uppercase;
          letter-spacing: .12em;
        }

        .fd-node {
          position: absolute;
          width: 138px;
          min-height: 52px;
          display: grid;
          grid-template-columns: 7px 1fr auto;
          gap: 8px;
          align-items: center;
          padding: 9px 10px;
          border: 1px solid rgba(255,255,255,.09);
          border-radius: 6px;
          background: #101010;
          box-shadow: 0 12px 30px rgba(0,0,0,.3);
        }

        .fd-node-one { left: 3%; top: 70px; }
        .fd-node-two { left: 31%; top: 32px; }
        .fd-node-three { left: 58%; top: 88px; }
        .fd-node-four { right: 3%; top: 32px; }

        .fd-node-ok { animation: fd-node-in .65s ease both; }
        .fd-node-two { animation-delay: .12s; }
        .fd-node-three { animation-delay: .24s; }
        .fd-node-four { animation-delay: .36s; }

        .fd-node-alert {
          border-color: rgba(245,158,11,.22);
          background: linear-gradient(135deg, #11100c, #101010);
        }

        .fd-dot {
          width: 7px;
          height: 7px;
          border-radius: 50%;
          background: #d6d6d6;
          box-shadow: 0 0 9px rgba(255,255,255,.4);
        }

        .fd-node-alert .fd-dot {
          background: #f59e0b;
          box-shadow: 0 0 12px rgba(245,158,11,.7);
          animation: fd-pulse 1.1s infinite;
        }

        .fd-node b,
        .fd-node small {
          display: block;
        }

        .fd-node b { font-size: 9px; font-weight: 500; }
        .fd-node small { margin-top: 3px; color: rgba(255,255,255,.36); font-size: 7px; }
        .fd-node em { color: rgba(255,255,255,.38); font-size: 7px; font-style: normal; }
        .fd-node-alert em { color: #eab65c; }

        .fd-line {
          position: absolute;
          height: 1px;
          background: rgba(255,255,255,.12);
          transform-origin: left center;
          overflow: visible;
        }

        .fd-line-a {
          width: 150px;
          left: 22%;
          top: 78px;
          transform: rotate(-20deg);
        }

        .fd-line-b {
          width: 145px;
          left: 48%;
          top: 79px;
          transform: rotate(18deg);
        }

        .fd-line i {
          position: absolute;
          left: -12px;
          top: -2px;
          width: 18px;
          height: 5px;
          border-radius: 50%;
          background: #fff;
          filter: blur(2px);
          animation: fd-flow 1.5s linear infinite;
        }

        .fd-line-b i { animation-delay: .7s; }

        .fd-diagnosis {
          display: grid;
          grid-template-columns: 34px 1fr auto;
          align-items: center;
          gap: 12px;
          margin-top: 14px;
          padding: 13px;
          border: 1px solid rgba(245,158,11,.16);
          border-radius: 6px;
          background: rgba(245,158,11,.035);
          animation: fd-diagnosis-in .7s .45s ease both;
        }

        .fd-ai {
          width: 34px;
          height: 34px;
          display: grid;
          place-items: center;
          border: 1px solid rgba(245,158,11,.2);
          border-radius: 6px;
          color: #f4bf68;
          background: rgba(245,158,11,.07);
          animation: fd-ai-glow 2s ease-in-out infinite;
        }

        .fd-ai-copy span,
        .fd-ai-copy strong,
        .fd-ai-copy p {
          display: block;
        }

        .fd-ai-copy span {
          color: rgba(255,255,255,.4);
          font-size: 8px;
          text-transform: uppercase;
          letter-spacing: .08em;
        }

        .fd-ai-copy strong {
          margin-top: 4px;
          font-size: 10px;
        }

        .fd-ai-copy p {
          margin: 4px 0 0;
          color: rgba(255,255,255,.42);
          font-size: 8px;
        }

        .fd-ai-copy b { color: rgba(255,255,255,.72); font-weight: 500; }

        .fd-action {
          display: inline-flex;
          align-items: center;
          gap: 5px;
          padding: 7px 9px;
          border: 1px solid rgba(255,255,255,.08);
          border-radius: 5px;
          color: rgba(255,255,255,.65);
          background: #111;
          font-size: 8px;
        }

        .fd-bottom {
          display: flex;
          align-items: center;
          justify-content: space-between;
          gap: 10px;
          margin-top: 12px;
          padding-top: 11px;
          border-top: 1px solid rgba(255,255,255,.06);
          color: rgba(255,255,255,.3);
          font-size: 7px;
        }

        .fd-bottom span {
          display: inline-flex;
          align-items: center;
          gap: 5px;
        }

        .fd-bottom b {
          color: rgba(255,255,255,.72);
          font-weight: 500;
        }

        @keyframes fd-pulse {
          0%,100% { opacity: .55; transform: scale(.8); }
          50% { opacity: 1; transform: scale(1.15); }
        }

        @keyframes fd-node-in {
          from { opacity: 0; transform: translateY(12px) scale(.97); }
          to { opacity: 1; transform: translateY(0) scale(1); }
        }

        @keyframes fd-flow {
          from { left: -15px; }
          to { left: calc(100% + 8px); }
        }

        @keyframes fd-diagnosis-in {
          from { opacity: 0; transform: translateY(8px); }
          to { opacity: 1; transform: translateY(0); }
        }

        @keyframes fd-ai-glow {
          0%,100% { box-shadow: 0 0 0 rgba(245,158,11,0); }
          50% { box-shadow: 0 0 22px rgba(245,158,11,.12); }
        }

        @media (max-width: 700px) {
          .flowops-diagnosis { padding: 16px; min-height: auto; }
          .fd-stats { grid-template-columns: 1fr; }
          .fd-map { height: 210px; overflow-x: auto; }
          .fd-node-one { left: 8px; }
          .fd-node-two { left: 165px; }
          .fd-node-three { left: 322px; }
          .fd-node-four { left: 479px; }
          .fd-diagnosis { grid-template-columns: 34px 1fr; }
          .fd-action { grid-column: 2; justify-self: start; }
          .fd-bottom { flex-wrap: wrap; }
        }



        /* ==============================================================
           Detect / Diagnose / Resolve — Vantis Reliability Control Plane
           A visual "nervous system" for workflows rather than a generic UI.
           ============================================================== */
        .reliability-control-plane {
          position: relative;
          min-height: 520px;
          padding: 24px;
          overflow: hidden;
          background: #080808;
          border: 1px solid rgba(255,255,255,.08);
          border-radius: 7px;
          box-shadow: 0 30px 90px rgba(0,0,0,.45);
        }

        .reliability-control-plane::before {
          content: "";
          position: absolute;
          inset: 0;
          background:
            linear-gradient(rgba(255,255,255,.018) 1px, transparent 1px),
            linear-gradient(90deg, rgba(255,255,255,.018) 1px, transparent 1px);
          background-size: 32px 32px;
          mask-image: linear-gradient(to bottom, rgba(0,0,0,.85), transparent 92%);
          pointer-events: none;
        }

        .reliability-control-plane::after {
          content: "";
          position: absolute;
          width: 420px;
          height: 420px;
          left: 50%;
          top: 72px;
          transform: translateX(-50%);
          border-radius: 50%;
          background: radial-gradient(circle, rgba(255,255,255,.035), transparent 66%);
          pointer-events: none;
        }

        .reliability-control-plane > * {
          position: relative;
          z-index: 2;
        }

        .rcp-top {
          display: flex;
          align-items: flex-start;
          justify-content: space-between;
          padding-bottom: 17px;
          border-bottom: 1px solid rgba(255,255,255,.07);
        }

        .rcp-top h2 {
          max-width: 440px;
          margin: 7px 0 0;
          font-size: 21px;
          line-height: 1.05;
          letter-spacing: -.045em;
        }

        .rcp-live {
          display: inline-flex;
          align-items: center;
          gap: 7px;
          padding: 7px 9px;
          border: 1px solid rgba(255,255,255,.09);
          border-radius: 6px;
          color: rgba(255,255,255,.48);
          font-size: 8px;
          letter-spacing: .08em;
        }

        .rcp-live i,
        .rcp-bottom i {
          width: 6px;
          height: 6px;
          display: inline-block;
          border-radius: 50%;
          background: #7df2a8;
          box-shadow: 0 0 12px rgba(125,242,168,.6);
        }

        .rcp-live i {
          animation: rcp-live 1.2s ease-in-out infinite;
        }

        .rcp-core {
          min-height: 190px;
          display: flex;
          align-items: center;
          gap: 38px;
          padding: 20px 8px 15px;
        }

        .rcp-radar {
          width: 178px;
          height: 178px;
          flex: 0 0 178px;
          position: relative;
          display: grid;
          place-items: center;
          border-radius: 50%;
        }

        .radar-ring {
          position: absolute;
          border: 1px solid rgba(255,255,255,.10);
          border-radius: 50%;
        }

        .ring-one { inset: 8px; }
        .ring-two { inset: 35px; }
        .ring-three { inset: 62px; }

        .radar-ring::after {
          content: "";
          position: absolute;
          inset: -1px;
          border-radius: inherit;
          border-top: 1px solid rgba(255,255,255,.55);
          animation: rcp-orbit 4s linear infinite;
        }

        .radar-sweep {
          position: absolute;
          inset: 9px;
          border-radius: 50%;
          background: conic-gradient(from 0deg, transparent 0 72%, rgba(255,255,255,.10) 82%, transparent 92%);
          mask-image: radial-gradient(circle, transparent 0 8%, #000 9% 100%);
          animation: rcp-sweep 3.8s linear infinite;
        }

        .radar-core {
          width: 66px;
          height: 66px;
          display: grid;
          place-items: center;
          align-content: center;
          border: 1px solid rgba(255,255,255,.16);
          border-radius: 50%;
          background: #0b0b0b;
          box-shadow: 0 0 35px rgba(255,255,255,.06), inset 0 0 22px rgba(255,255,255,.025);
          animation: rcp-core 2.5s ease-in-out infinite;
        }

        .radar-core b {
          font-size: 21px;
          line-height: 1;
          letter-spacing: -.04em;
        }

        .radar-core small {
          margin-top: 3px;
          color: rgba(255,255,255,.36);
          font-size: 7px;
          letter-spacing: .1em;
        }

        .radar-ping {
          position: absolute;
          width: 7px;
          height: 7px;
          border-radius: 50%;
          background: #f59e0b;
          box-shadow: 0 0 15px rgba(245,158,11,.75);
        }

        .ping-one { top: 23px; right: 39px; animation: rcp-ping 1.7s infinite; }
        .ping-two { bottom: 33px; left: 25px; animation: rcp-ping 2.1s .4s infinite; }
        .ping-three { top: 91px; right: 7px; width: 5px; height: 5px; animation: rcp-ping 1.9s .8s infinite; }

        .rcp-core-copy {
          max-width: 340px;
        }

        .rcp-label,
        .rcp-core-copy strong,
        .rcp-core-copy p {
          display: block;
        }

        .rcp-label {
          color: rgba(255,255,255,.34);
          font-size: 8px;
          letter-spacing: .13em;
        }

        .rcp-core-copy strong {
          margin-top: 7px;
          font-size: 19px;
          letter-spacing: -.035em;
        }

        .rcp-core-copy p {
          margin: 8px 0 13px;
          color: rgba(255,255,255,.43);
          font-size: 10px;
          line-height: 1.55;
        }

        .rcp-progress {
          height: 3px;
          overflow: hidden;
          border-radius: 2px;
          background: rgba(255,255,255,.07);
        }

        .rcp-progress i {
          display: block;
          width: 87%;
          height: 100%;
          background: rgba(255,255,255,.8);
          transform-origin: left;
          animation: rcp-progress 1.5s ease both;
        }

        .rcp-mini-stats {
          display: flex;
          gap: 16px;
          margin-top: 11px;
        }

        .rcp-mini-stats span {
          color: rgba(255,255,255,.3);
          font-size: 7px;
        }

        .rcp-mini-stats b {
          color: rgba(255,255,255,.7);
          font-weight: 500;
        }

        .rcp-section-head {
          display: flex;
          align-items: center;
          justify-content: space-between;
          margin: 1px 0 8px;
          color: rgba(255,255,255,.3);
          font-size: 7px;
          letter-spacing: .12em;
        }

        .rcp-section-head small {
          color: rgba(255,255,255,.23);
          font-size: 7px;
          letter-spacing: 0;
        }

        .rcp-signals {
          display: grid;
          grid-template-columns: repeat(2, 1fr);
          gap: 6px;
        }

        .rcp-signal {
          min-height: 48px;
          display: grid;
          grid-template-columns: 20px 1fr auto;
          align-items: center;
          gap: 7px;
          padding: 8px 10px;
          border: 1px solid rgba(255,255,255,.065);
          border-radius: 6px;
          background: #0b0b0b;
          animation: rcp-signal-in .55s ease both;
        }

        .rcp-signal.danger {
          border-color: rgba(245,158,11,.16);
          background: rgba(245,158,11,.025);
        }

        .signal-id {
          color: rgba(255,255,255,.23);
          font-size: 7px;
          font-variant-numeric: tabular-nums;
        }

        .signal-copy b,
        .signal-copy small {
          display: block;
        }

        .signal-copy b {
          font-size: 8px;
          font-weight: 500;
        }

        .signal-copy small {
          margin-top: 3px;
          color: rgba(255,255,255,.34);
          font-size: 7px;
        }

        .signal-state {
          display: inline-flex;
          align-items: center;
          gap: 4px;
          color: rgba(255,255,255,.28);
          font-size: 6px;
          text-transform: uppercase;
          letter-spacing: .06em;
        }

        .signal-state i {
          width: 5px;
          height: 5px;
          border-radius: 50%;
          background: #f59e0b;
        }

        .rcp-signal.ok .signal-state i {
          background: #7df2a8;
          box-shadow: 0 0 8px rgba(125,242,168,.5);
        }

        .rcp-action {
          display: grid;
          grid-template-columns: 34px 1fr 28px;
          align-items: center;
          gap: 11px;
          margin-top: 10px;
          padding: 10px 11px;
          border: 1px solid rgba(255,255,255,.09);
          border-radius: 6px;
          background: linear-gradient(90deg, #101010, #0b0b0b);
          animation: rcp-action-in .7s .5s ease both;
        }

        .action-orbit {
          width: 34px;
          height: 34px;
          display: grid;
          place-items: center;
          border: 1px solid rgba(255,255,255,.12);
          border-radius: 50%;
          color: #fff;
          font-size: 13px;
          animation: rcp-orbit 3s linear infinite;
        }

        .action-copy span,
        .action-copy strong,
        .action-copy p {
          display: block;
        }

        .action-copy span {
          color: rgba(255,255,255,.3);
          font-size: 6px;
          letter-spacing: .13em;
        }

        .action-copy strong {
          margin-top: 3px;
          font-size: 9px;
          font-weight: 500;
        }

        .action-copy p {
          margin: 3px 0 0;
          color: rgba(255,255,255,.35);
          font-size: 7px;
        }

        .action-arrow {
          width: 28px;
          height: 28px;
          display: grid;
          place-items: center;
          border: 1px solid rgba(255,255,255,.08);
          border-radius: 5px;
          color: rgba(255,255,255,.6);
        }

        .rcp-bottom {
          display: flex;
          align-items: center;
          justify-content: space-between;
          gap: 10px;
          margin-top: 11px;
          padding-top: 10px;
          border-top: 1px solid rgba(255,255,255,.055);
          color: rgba(255,255,255,.26);
          font-size: 7px;
        }

        .rcp-bottom span {
          display: inline-flex;
          align-items: center;
          gap: 5px;
        }

        .rcp-bottom b {
          color: rgba(255,255,255,.65);
          font-weight: 500;
        }

        @keyframes rcp-live {
          0%,100% { opacity: .45; transform: scale(.8); }
          50% { opacity: 1; transform: scale(1.15); }
        }

        @keyframes rcp-sweep {
          to { transform: rotate(360deg); }
        }

        @keyframes rcp-orbit {
          to { transform: rotate(360deg); }
        }

        @keyframes rcp-core {
          0%,100% { transform: scale(1); box-shadow: 0 0 35px rgba(255,255,255,.05), inset 0 0 22px rgba(255,255,255,.025); }
          50% { transform: scale(1.045); box-shadow: 0 0 48px rgba(255,255,255,.10), inset 0 0 25px rgba(255,255,255,.04); }
        }

        @keyframes rcp-ping {
          0%,100% { opacity: .35; transform: scale(.7); }
          50% { opacity: 1; transform: scale(1.5); }
        }

        @keyframes rcp-progress {
          from { transform: scaleX(0); }
          to { transform: scaleX(1); }
        }

        @keyframes rcp-signal-in {
          from { opacity: 0; transform: translateX(12px); }
          to { opacity: 1; transform: translateX(0); }
        }

        @keyframes rcp-action-in {
          from { opacity: 0; transform: translateY(8px); }
          to { opacity: 1; transform: translateY(0); }
        }

        @media (max-width: 800px) {
          .reliability-control-plane { min-height: auto; padding: 18px; }
          .rcp-core { gap: 20px; }
          .rcp-radar { width: 140px; height: 140px; flex-basis: 140px; }
          .rcp-mini-stats { flex-wrap: wrap; }
        }

        @media (max-width: 600px) {
          .rcp-top { gap: 10px; }
          .rcp-top h2 { font-size: 16px; }
          .rcp-core { flex-direction: column; align-items: flex-start; padding-left: 0; }
          .rcp-radar { align-self: center; }
          .rcp-signals { grid-template-columns: 1fr; }
          .rcp-bottom { flex-wrap: wrap; }
        }

        @media (prefers-reduced-motion: reduce) {
          .reliability-control-plane * {
            animation: none !important;
          }
        }




        /* ==============================================================
           Detect · Diagnose · Resolve — execution trace console
           ============================================================== */
        .flowops-trace-console {
          position: relative;
          width: min(100%, 720px);
          margin-left: auto;
          overflow: hidden;
          border: 1px solid rgba(255,255,255,.10);
          border-radius: 7px;
          background: #090909;
          box-shadow: 0 30px 80px rgba(0,0,0,.42);
        }

        .ftc-header,
        .ftc-summary,
        .ftc-footer {
          display: flex;
          align-items: center;
          justify-content: space-between;
        }

        .ftc-header {
          min-height: 43px;
          padding: 0 15px;
          border-bottom: 1px solid rgba(255,255,255,.07);
          background: #0b0b0b;
        }

        .ftc-breadcrumb {
          display: flex;
          align-items: center;
          gap: 7px;
          min-width: 0;
          color: rgba(255,255,255,.28);
          font-size: 7px;
        }

        .ftc-breadcrumb i {
          color: rgba(255,255,255,.18);
          font-style: normal;
        }

        .ftc-breadcrumb b {
          overflow: hidden;
          color: rgba(255,255,255,.62);
          font-weight: 500;
          text-overflow: ellipsis;
          white-space: nowrap;
        }

        .ftc-status {
          display: inline-flex;
          align-items: center;
          gap: 6px;
          padding: 5px 8px;
          border: 1px solid rgba(245,158,11,.18);
          border-radius: 5px;
          color: #dcae59;
          font-size: 7px;
        }

        .ftc-status i {
          width: 5px;
          height: 5px;
          border-radius: 50%;
          background: #f59e0b;
          box-shadow: 0 0 8px rgba(245,158,11,.55);
        }

        .ftc-summary {
          align-items: stretch;
          border-bottom: 1px solid rgba(255,255,255,.07);
        }

        .ftc-summary-main {
          flex: 1;
          padding: 15px;
          border-right: 1px solid rgba(255,255,255,.07);
        }

        .ftc-summary-main span,
        .ftc-summary-main strong,
        .ftc-summary-main small,
        .ftc-metric span,
        .ftc-metric b {
          display: block;
        }

        .ftc-summary-main span,
        .ftc-metric span {
          color: rgba(255,255,255,.27);
          font-size: 6px;
          letter-spacing: .12em;
        }

        .ftc-summary-main strong {
          margin-top: 4px;
          font-size: 13px;
          font-weight: 500;
        }

        .ftc-summary-main small {
          margin-top: 4px;
          color: rgba(255,255,255,.31);
          font-size: 7px;
        }

        .ftc-metric {
          width: 115px;
          padding: 15px 12px;
          border-right: 1px solid rgba(255,255,255,.07);
        }

        .ftc-metric:last-child {
          border-right: 0;
        }

        .ftc-metric b {
          margin-top: 6px;
          font-size: 13px;
          font-weight: 500;
        }

        .ftc-body {
          display: grid;
          grid-template-columns: 1.15fr .85fr;
          min-height: 275px;
        }

        .ftc-trace {
          border-right: 1px solid rgba(255,255,255,.07);
        }

        .ftc-trace-head {
          display: flex;
          justify-content: space-between;
          padding: 12px 15px 9px;
          color: rgba(255,255,255,.26);
          font-size: 6px;
          letter-spacing: .11em;
        }

        .trace-row {
          position: relative;
          display: grid;
          grid-template-columns: 30px 1fr 48px;
          align-items: center;
          min-height: 55px;
          padding: 0 15px 0 12px;
          border-top: 1px solid rgba(255,255,255,.045);
          transition: background .2s ease;
        }

        .trace-row.selected {
          background: rgba(255,255,255,.035);
        }

        .trace-marker {
          position: relative;
          align-self: stretch;
          display: grid;
          place-items: center;
        }

        .trace-marker::after {
          content: "";
          position: absolute;
          top: 50%;
          left: 50%;
          width: 1px;
          height: 100%;
          background: rgba(255,255,255,.10);
        }

        .trace-row:last-child .trace-marker::after {
          display: none;
        }

        .trace-marker i {
          position: relative;
          z-index: 1;
          width: 7px;
          height: 7px;
          border: 2px solid #090909;
          border-radius: 50%;
          background: #bcbcbc;
          box-shadow: 0 0 0 1px rgba(255,255,255,.18);
        }

        .trace-warn .trace-marker i {
          background: #f59e0b;
          box-shadow: 0 0 10px rgba(245,158,11,.55);
          animation: ftc-warn 1.3s ease-in-out infinite;
        }

        .trace-fail .trace-marker i {
          background: #777;
        }

        .trace-info b,
        .trace-info small {
          display: block;
        }

        .trace-info b {
          color: rgba(255,255,255,.72);
          font-size: 8px;
          font-weight: 500;
        }

        .trace-info small {
          margin-top: 4px;
          color: rgba(255,255,255,.28);
          font-size: 6px;
        }

        .trace-time {
          color: rgba(255,255,255,.34);
          font-size: 7px;
          text-align: right;
          font-variant-numeric: tabular-nums;
        }

        .trace-warn .trace-time {
          color: #dcae59;
        }

        .ftc-detail {
          padding: 15px;
          background: #0a0a0a;
        }

        .detail-head span,
        .detail-head b {
          display: block;
        }

        .detail-head span {
          color: rgba(255,255,255,.25);
          font-size: 6px;
          letter-spacing: .11em;
        }

        .detail-head b {
          margin-top: 5px;
          color: rgba(255,255,255,.72);
          font-size: 10px;
          font-weight: 500;
        }

        .latency-chart {
          position: relative;
          height: 75px;
          margin: 17px 0 12px;
          overflow: hidden;
          border-bottom: 1px solid rgba(255,255,255,.08);
        }

        .chart-grid {
          position: absolute;
          inset: 0;
          background:
            linear-gradient(rgba(255,255,255,.025) 1px, transparent 1px),
            linear-gradient(90deg, rgba(255,255,255,.025) 1px, transparent 1px);
          background-size: 25px 18px;
        }

        .chart-baseline {
          position: absolute;
          left: 0;
          right: 0;
          bottom: 18px;
          height: 1px;
          background: rgba(255,255,255,.22);
        }

        .chart-baseline i {
          position: absolute;
          left: 0;
          top: -2px;
          width: 55%;
          height: 4px;
          border-radius: 50%;
          background: rgba(255,255,255,.45);
          filter: blur(1.5px);
        }

        .chart-spike {
          position: absolute;
          left: 51%;
          right: 0;
          bottom: 18px;
          height: 43px;
          border-top: 1px solid #dcae59;
          transform: skewY(-27deg);
          transform-origin: left bottom;
        }

        .chart-spike i {
          position: absolute;
          right: 7%;
          top: -3px;
          width: 6px;
          height: 6px;
          border-radius: 50%;
          background: #f59e0b;
          box-shadow: 0 0 12px rgba(245,158,11,.65);
        }

        .chart-tag {
          position: absolute;
          top: 7px;
          right: 6px;
          padding: 3px 5px;
          border: 1px solid rgba(245,158,11,.18);
          border-radius: 3px;
          color: #dcae59;
          background: rgba(245,158,11,.04);
          font-size: 6px;
        }

        .detail-row {
          display: flex;
          justify-content: space-between;
          padding: 6px 0;
          border-bottom: 1px solid rgba(255,255,255,.045);
          color: rgba(255,255,255,.29);
          font-size: 7px;
        }

        .detail-row b {
          color: rgba(255,255,255,.62);
          font-weight: 500;
        }

        .detail-row .warn-text {
          color: #dcae59;
        }

        .detail-note {
          margin-top: 12px;
          padding: 9px;
          border-left: 2px solid rgba(245,158,11,.45);
          background: rgba(245,158,11,.025);
        }

        .detail-note span {
          color: rgba(255,255,255,.27);
          font-size: 6px;
        }

        .detail-note p {
          margin: 4px 0 0;
          color: rgba(255,255,255,.43);
          font-size: 7px;
          line-height: 1.45;
        }

        .ftc-footer {
          min-height: 35px;
          padding: 0 15px;
          border-top: 1px solid rgba(255,255,255,.07);
          color: rgba(255,255,255,.26);
          font-size: 6px;
        }

        .ftc-footer span {
          display: inline-flex;
          align-items: center;
          gap: 5px;
        }

        .ftc-footer span:first-child i {
          width: 5px;
          height: 5px;
          border-radius: 50%;
          background: #7df2a8;
          box-shadow: 0 0 7px rgba(125,242,168,.5);
        }

        .ftc-footer b {
          color: rgba(255,255,255,.56);
          font-weight: 500;
        }

        .ftc-footer span:last-child {
          color: rgba(255,255,255,.55);
        }

        @keyframes ftc-warn {
          0%,100% { transform: scale(.8); opacity: .6; }
          50% { transform: scale(1.2); opacity: 1; }
        }

        @media (max-width: 760px) {
          .ftc-body {
            grid-template-columns: 1fr;
          }

          .ftc-trace {
            border-right: 0;
            border-bottom: 1px solid rgba(255,255,255,.07);
          }

          .ftc-metric {
            width: 95px;
          }
        }

        @media (max-width: 560px) {
          .flowops-trace-console {
            width: 100%;
          }

          .ftc-summary {
            flex-wrap: wrap;
          }

          .ftc-summary-main {
            width: 100%;
            flex: auto;
            border-right: 0;
            border-bottom: 1px solid rgba(255,255,255,.07);
          }

          .ftc-metric {
            flex: 1;
            width: auto;
          }

          .ftc-footer {
            flex-wrap: wrap;
            gap: 7px;
            padding: 8px 12px;
          }
        }



        /* ---- Wide Detect/Diagnose/Resolve layout ---- */
        .wide-panel {
          position: relative;
          display: grid !important;
          grid-template-columns: minmax(280px, .78fr) minmax(560px, 1.22fr) !important;
          align-items: center !important;
          column-gap: clamp(48px, 7vw, 110px) !important;
          padding: clamp(80px, 9vw, 128px) clamp(24px, 5vw, 72px) !important;
        }

        .wide-panel > .reveal-left {
          position: relative;
          z-index: 2;
          max-width: 500px;
        }

        .wide-panel > .reveal-left h2 {
          max-width: 470px;
          margin-top: 18px;
        }

        .wide-panel > .reveal-left p {
          max-width: 460px;
          margin-top: 18px;
          line-height: 1.7;
        }

        .wide-panel > .reveal-left .pills {
          margin-top: 26px;
          gap: 8px;
        }

        .wide-panel > .reveal-right {
          width: 100%;
          min-width: 0;
          position: relative;
          z-index: 2;
        }

        .flowops-trace-console {
          width: 100% !important;
          max-width: 720px !important;
          margin: 0 0 0 auto !important;
        }

        @media (max-width: 1100px) {
          .wide-panel {
            grid-template-columns: minmax(260px, .72fr) minmax(500px, 1.28fr) !important;
            column-gap: 48px !important;
            padding-left: 40px !important;
            padding-right: 40px !important;
          }
        }

        @media (max-width: 900px) {
          .wide-panel {
            grid-template-columns: 1fr !important;
            row-gap: 48px !important;
            padding: 72px 24px !important;
          }

          .wide-panel > .reveal-left {
            max-width: 620px;
          }

          .wide-panel > .reveal-right {
            width: 100%;
          }

          .flowops-trace-console {
            max-width: none !important;
          }
        }

        @media (max-width: 560px) {
          .wide-panel {
            row-gap: 36px !important;
            padding: 60px 18px !important;
          }

          .wide-panel > .reveal-left h2 {
            margin-top: 14px;
          }

          .wide-panel > .reveal-left p {
            margin-top: 14px;
          }

          .wide-panel > .reveal-left .pills {
            margin-top: 20px;
          }
        }


        /* ---- Get Started CTA spacing ---- */
        #cta .reveal-fade {
          display: flex;
          flex-direction: column;
          align-items: center;
        }

        #cta .eyebrow {
          margin-bottom: 22px !important;
        }

        #cta h2 {
          margin: 0 !important;
        }

        #cta p {
          margin-top: 20px !important;
          margin-bottom: 0 !important;
        }

        #cta .buttons {
          display: flex;
          align-items: center;
          justify-content: center;
          gap: 12px !important;
          margin-top: 30px !important;
        }

        #cta .buttons .white-button,
        #cta .buttons .outline-button {
          min-width: 148px;
          min-height: 44px;
          padding: 0 18px !important;
          display: inline-flex;
          align-items: center;
          justify-content: center;
          gap: 8px;
        }

        @media (max-width: 640px) {
          #cta .buttons {
            width: 100%;
            margin-top: 26px !important;
            gap: 10px !important;
          }

          #cta .buttons .white-button,
          #cta .buttons .outline-button {
            min-width: 0;
            flex: 1;
          }
        }

        /* ---- Vantis-native "messy middle" section ---- */
        .flowops-messy-middle {
          position: relative;
          overflow: hidden;
          padding: clamp(112px, 11vw, 172px) 24px clamp(110px, 10vw, 160px) !important;
        }

        .messy-middle-intro {
          width: min(850px, 100%);
          margin: 0 auto;
          text-align: center;
        }

        .messy-middle-intro h2 {
          margin-top: 18px !important;
          font-size: clamp(42px, 4.5vw, 64px) !important;
          line-height: .97 !important;
          letter-spacing: -.06em !important;
          text-wrap: balance;
        }

        .messy-middle-intro p {
          width: min(690px, 100%);
          margin: 20px auto 0 !important;
          color: rgba(255,255,255,.46) !important;
          font-size: 15px !important;
          line-height: 1.65 !important;
        }

        .messy-middle-console {
          width: min(1080px, 100%);
          margin: 56px auto 0;
          border: 1px solid rgba(255,255,255,.085);
          border-radius: 10px;
          overflow: hidden;
          background: #0b0c0d;
          box-shadow: 0 28px 90px rgba(0,0,0,.28), inset 0 1px 0 rgba(255,255,255,.025);
        }

        .mm-console-head,
        .mm-console-foot {
          min-height: 46px;
          padding: 0 18px;
          display: flex;
          align-items: center;
          justify-content: space-between;
          border-bottom: 1px solid rgba(255,255,255,.06);
          color: rgba(255,255,255,.3);
          font-size: 9px;
          letter-spacing: .08em;
        }

        .mm-console-foot {
          border-top: 1px solid rgba(255,255,255,.06);
          border-bottom: 0;
          letter-spacing: .02em;
          text-transform: none;
        }

        .mm-title-group {
          display: flex;
          align-items: center;
          gap: 9px;
        }

        .mm-title-group b {
          margin-left: 4px;
          padding: 5px 7px;
          border: 1px solid rgba(255,255,255,.07);
          border-radius: 4px;
          color: rgba(255,255,255,.42);
          font-size: 8px;
          font-weight: 500;
          letter-spacing: .02em;
          text-transform: none;
        }

        .mm-dot {
          width: 6px;
          height: 6px;
          border-radius: 50%;
          background: rgba(114,217,161,.8);
          box-shadow: 0 0 9px rgba(114,217,161,.3);
        }

        .mm-time {
          color: rgba(255,255,255,.22);
        }

        .mm-console-body {
          display: grid;
          grid-template-columns: minmax(0, 1fr) 230px;
        }

        .mm-workflow {
          padding: 25px;
          border-right: 1px solid rgba(255,255,255,.06);
        }

        .mm-workflow-head {
          display: flex;
          align-items: flex-start;
          justify-content: space-between;
          gap: 20px;
        }

        .mm-workflow-head > div {
          display: flex;
          flex-direction: column;
          gap: 7px;
        }

        .mm-workflow-head span:first-child {
          color: rgba(255,255,255,.24);
          font-size: 8px;
          letter-spacing: .1em;
        }

        .mm-workflow-head strong {
          color: rgba(255,255,255,.76);
          font-size: 14px;
          font-weight: 550;
          letter-spacing: -.025em;
        }

        .mm-degraded {
          padding: 6px 8px;
          border: 1px solid rgba(246,181,72,.18);
          border-radius: 4px;
          color: rgba(246,181,72,.7);
          background: rgba(246,181,72,.045);
          font-size: 8px;
          letter-spacing: .08em;
        }

        .mm-flow {
          margin-top: 28px;
          display: flex;
          align-items: center;
          gap: 0;
        }

        .mm-node {
          min-width: 0;
          width: 145px;
          padding: 12px;
          border: 1px solid rgba(255,255,255,.065);
          border-radius: 6px;
          background: rgba(255,255,255,.018);
          display: grid;
          grid-template-columns: 22px 1fr;
          column-gap: 8px;
          align-items: center;
        }

        .mm-node-icon {
          width: 21px;
          height: 21px;
          display: grid;
          place-items: center;
          grid-row: span 2;
          border-radius: 5px;
          background: rgba(255,255,255,.045);
          color: rgba(255,255,255,.5);
          font-size: 10px;
        }

        .mm-node b {
          overflow: hidden;
          text-overflow: ellipsis;
          white-space: nowrap;
          color: rgba(255,255,255,.65);
          font-size: 10px;
          font-weight: 550;
        }

        .mm-node small {
          margin-top: 4px;
          color: rgba(255,255,255,.27);
          font-size: 8px;
        }

        .mm-warn {
          border-color: rgba(246,181,72,.2);
          background: rgba(246,181,72,.035);
        }

        .mm-warn .mm-node-icon {
          color: rgba(246,181,72,.8);
          background: rgba(246,181,72,.08);
        }

        .mm-fail {
          border-color: rgba(244,108,108,.18);
          background: rgba(244,108,108,.025);
        }

        .mm-fail .mm-node-icon {
          color: rgba(244,108,108,.75);
          background: rgba(244,108,108,.07);
        }

        .mm-connector {
          position: relative;
          width: 22px;
          height: 1px;
          flex: 0 0 22px;
          background: rgba(255,255,255,.1);
        }

        .mm-connector::after {
          content: "";
          position: absolute;
          width: 3px;
          height: 3px;
          top: -1px;
          left: 4px;
          border-radius: 50%;
          background: rgba(255,255,255,.38);
          animation: mm-signal 2.8s ease-in-out infinite;
        }

        .mm-insight {
          margin-top: 22px;
          padding: 14px 15px;
          border-left: 1px solid rgba(246,181,72,.42);
          background: linear-gradient(90deg, rgba(246,181,72,.035), transparent);
        }

        .mm-insight > span {
          color: rgba(246,181,72,.65);
          font-size: 8px;
          letter-spacing: .09em;
        }

        .mm-insight p {
          max-width: 570px;
          margin-top: 7px;
          color: rgba(255,255,255,.44);
          font-size: 10px;
          line-height: 1.55;
        }

        .mm-insight p b {
          color: rgba(255,255,255,.7);
          font-weight: 550;
        }

        .mm-insight a {
          display: inline-flex;
          align-items: center;
          gap: 5px;
          margin-top: 9px;
          color: rgba(255,255,255,.58);
          font-size: 9px;
          text-decoration: none;
        }

        .mm-stats {
          display: flex;
          flex-direction: column;
        }

        .mm-stat {
          flex: 1;
          padding: 20px 19px;
          border-bottom: 1px solid rgba(255,255,255,.055);
        }

        .mm-stat:last-child {
          border-bottom: 0;
        }

        .mm-stat span {
          display: block;
          color: rgba(255,255,255,.23);
          font-size: 8px;
          letter-spacing: .1em;
        }

        .mm-stat strong {
          display: block;
          margin-top: 9px;
          color: rgba(255,255,255,.78);
          font-size: 24px;
          line-height: 1;
          font-weight: 500;
          letter-spacing: -.04em;
        }

        .mm-stat small {
          display: block;
          margin-top: 7px;
          color: rgba(255,255,255,.28);
          font-size: 9px;
        }

        .mm-console-foot span:first-child {
          color: rgba(255,255,255,.38);
        }

        .mm-console-foot i {
          display: inline-block;
          width: 3px;
          height: 3px;
          margin: 0 7px;
          vertical-align: middle;
          border-radius: 50%;
          background: rgba(255,255,255,.16);
        }

        @keyframes mm-signal {
          0% { transform: translateX(0); opacity: .15; }
          45% { transform: translateX(11px); opacity: .8; }
          70%, 100% { transform: translateX(17px); opacity: 0; }
        }

        @media (max-width: 980px) {
          .mm-console-body {
            grid-template-columns: 1fr;
          }

          .mm-workflow {
            border-right: 0;
            border-bottom: 1px solid rgba(255,255,255,.06);
          }

          .mm-stats {
            display: grid;
            grid-template-columns: repeat(3, 1fr);
          }

          .mm-stat {
            border-bottom: 0;
            border-right: 1px solid rgba(255,255,255,.055);
          }

          .mm-stat:last-child {
            border-right: 0;
          }
        }

        @media (max-width: 700px) {
          .flowops-messy-middle {
            padding-inline: 18px !important;
          }

          .messy-middle-console {
            margin-top: 42px;
          }

          .mm-console-head,
          .mm-console-foot {
            padding-inline: 13px;
          }

          .mm-time {
            display: none;
          }

          .mm-workflow {
            padding: 18px;
          }

          .mm-flow {
            align-items: stretch;
            flex-direction: column;
            gap: 6px;
          }

          .mm-node {
            width: 100%;
          }

          .mm-connector {
            width: 1px;
            height: 12px;
            flex: 0 0 12px;
            margin-left: 20px;
          }

          .mm-connector::after {
            top: 4px;
            left: -1px;
          }

          .mm-stats {
            grid-template-columns: 1fr;
          }

          .mm-stat {
            border-right: 0;
            border-bottom: 1px solid rgba(255,255,255,.055);
            padding: 15px 18px;
          }

          .mm-stat:last-child {
            border-bottom: 0;
          }
        }

        /* ---- Cinematic Get Started CTA ---- */
        .flowops-get-started {
          position: relative;
          isolation: isolate;
          min-height: 25vh;
          height: 360px;
          margin: 0;
          padding: clamp(52px, 5vw, 76px) 32px;
          display: grid;
          place-items: center;
          overflow: hidden;
          border-top: 1px solid rgba(255,255,255,.06);
          border-bottom: 1px solid rgba(255,255,255,.06);
          background: #080909;
        }

        .get-started-image {
          position: absolute;
          z-index: -3;
          top: 18px;
          right: 28px;
          bottom: 18px;
          left: 28px;
          border-radius: 7px;
          background-image: url("https://images.unsplash.com/photo-1712554652588-3cb5dada0d07?auto=format&fit=crop&w=2400&q=88");
          background-size: cover;
          background-position: center 48%;
          background-repeat: no-repeat;
          opacity: .20;
          filter: none;
          transform: scale(1.01);
        }

        .get-started-overlay {
          position: absolute;
          z-index: -2;
          top: 18px;
          right: 28px;
          bottom: 18px;
          left: 28px;
          border-radius: 7px;
          pointer-events: none;
          background: transparent;
        }

        .get-started-content {
          width: min(860px, 100%);
          text-align: center;
          position: relative;
          z-index: 1;
        }

        .get-started-content .eyebrow {
          justify-content: center;
        }

        .get-started-content h2 {
          margin-top: 12px !important;
          font-size: clamp(38px, 4.4vw, 58px) !important;
          line-height: .95 !important;
          letter-spacing: -.06em !important;
          text-wrap: balance;
        }

        .get-started-content h2 em {
          font-style: normal;
          color: rgba(255,255,255,.58);
        }

        .get-started-content p {
          width: min(620px, 100%);
          margin: 14px auto 0 !important;
          color: rgba(255,255,255,.54) !important;
          font-size: 14px !important;
          line-height: 1.55 !important;
          text-wrap: balance;
        }

        .get-started-actions {
          display: flex;
          align-items: center;
          justify-content: center;
          gap: 9px;
          margin-top: 20px;
        }

        .get-started-actions .btn {
          min-height: 38px;
          padding: 0 15px;
          border-radius: 7px !important;
          display: inline-flex;
          align-items: center;
          justify-content: center;
          gap: 7px;
          text-decoration: none;
        }

        .get-started-note {
          margin-top: 16px;
          display: flex;
          align-items: center;
          justify-content: center;
          flex-wrap: wrap;
          gap: 9px;
          color: rgba(255,255,255,.29);
          font-size: 9px;
          letter-spacing: .02em;
        }

        .get-started-note i {
          width: 3px;
          height: 3px;
          border-radius: 50%;
          background: rgba(255,255,255,.2);
        }

        @media (max-width: 700px) {
          .flowops-get-started {
            min-height: 25vh;
            height: 390px;
            padding: 54px 20px;
          }

          .get-started-image,
          .get-started-overlay {
            top: 12px;
            right: 16px;
            bottom: 12px;
            left: 16px;
          }

          .get-started-actions {
            flex-direction: column;
            width: min(280px, 100%);
            margin-inline: auto;
          }

          .get-started-actions .btn {
            width: 100%;
          }

          .get-started-note {
            gap: 7px;
            line-height: 1.8;
          }
        }

        /* ---- Minimal Trust & Security ---- */
        #security {
          position: relative;
          overflow: hidden;
          padding-top: clamp(110px, 11vw, 164px) !important;
          padding-bottom: clamp(104px, 10vw, 150px) !important;
        }

        .trust-intro {
          max-width: 760px;
          margin-inline: auto;
          text-align: center;
        }

        .trust-intro h2 {
          margin-top: 18px !important;
          font-size: clamp(40px, 4vw, 58px) !important;
          line-height: 1 !important;
          letter-spacing: -.055em !important;
        }

        .trust-intro p {
          margin: 19px auto 0 !important;
          color: rgba(255,255,255,.45) !important;
          font-size: 15px !important;
          line-height: 1.6 !important;
        }

        .trust-grid {
          width: min(980px, 100%);
          margin: 58px auto 0;
          display: grid;
          grid-template-columns: repeat(3, minmax(0, 1fr));
          border-top: 1px solid rgba(255,255,255,.08);
          border-bottom: 1px solid rgba(255,255,255,.08);
        }

        .trust-item {
          min-height: 205px;
          padding: 25px 28px 27px;
        }

        .trust-item + .trust-item {
          border-left: 1px solid rgba(255,255,255,.08);
        }

        .trust-item-top {
          display: flex;
          align-items: center;
          gap: 12px;
        }

        .trust-number {
          color: rgba(255,255,255,.28);
          font-size: 10px;
          font-variant-numeric: tabular-nums;
          letter-spacing: .08em;
        }

        .trust-line {
          height: 1px;
          width: 34px;
          background: rgba(255,255,255,.13);
        }

        .trust-item h3 {
          margin-top: 27px;
          color: rgba(255,255,255,.84);
          font-size: 15px;
          line-height: 1.2;
          font-weight: 560;
          letter-spacing: -.02em;
        }

        .trust-item p {
          max-width: 250px;
          margin-top: 11px;
          color: rgba(255,255,255,.4);
          font-size: 12px;
          line-height: 1.65;
        }

        .trust-footer {
          display: flex;
          align-items: center;
          justify-content: center;
          flex-wrap: wrap;
          gap: 13px;
          margin-top: 25px;
          color: rgba(255,255,255,.28);
          font-size: 10px;
          letter-spacing: .025em;
        }

        .trust-footer i {
          width: 3px;
          height: 3px;
          border-radius: 50%;
          background: rgba(255,255,255,.2);
        }

        @media (max-width: 760px) {
          .trust-grid {
            grid-template-columns: 1fr;
            margin-top: 44px;
          }

          .trust-item {
            min-height: auto;
            padding: 23px 20px;
          }

          .trust-item + .trust-item {
            border-left: 0;
            border-top: 1px solid rgba(255,255,255,.08);
          }

          .trust-item p {
            max-width: 500px;
          }

          .trust-footer {
            gap: 9px;
            line-height: 1.7;
          }
        }


        /* ---- Workflow builder ---- */
        /* ---- Polished hero workflow builder ---- */
        .build-panel-polished { overflow: hidden; }
        .build-panel-heading { min-height: 68px; align-items: center; }
        .build-breadcrumb { display: flex; align-items: center; flex-wrap: wrap; gap: 7px; }
        .build-breadcrumb > span:not(.tiny) { color: rgba(255,255,255,.14); font-size: 9px; }
        .build-breadcrumb h2 { width: 100%; margin-top: 3px; font-size: 15px; letter-spacing: -.02em; }
        .build-heading-actions { display: flex; align-items: center; gap: 10px; }
        .build-heading-actions button { display:inline-flex; align-items:center; gap:6px; min-height:29px; padding:0 9px; border:1px solid rgba(255,255,255,.11); border-radius:6px; background:rgba(255,255,255,.045); color:rgba(255,255,255,.68); font-size:9px; }
        .build-heading-actions .status { white-space: nowrap; }
        .build-preview-polished { min-height: 330px; }
        .build-sidebar-polished { width: 152px; padding: 15px 10px; }
        .build-sidebar-head { display:flex; align-items:center; justify-content:space-between; padding:0 5px 10px; }
        .build-sidebar-head button { width:22px; height:22px; border:1px solid rgba(255,255,255,.1); border-radius:5px; background:rgba(255,255,255,.035); color:rgba(255,255,255,.55); }
        .build-tool { display:grid; grid-template-columns:23px 1fr 12px; align-items:center; gap:7px; min-height:42px; padding:0 7px; border:1px solid transparent; border-radius:7px; color:rgba(255,255,255,.46); }
        .build-tool.active { border-color:rgba(255,255,255,.095); background:rgba(255,255,255,.045); color:rgba(255,255,255,.78); }
        .build-tool b { display:grid; place-items:center; width:22px; height:22px; border:1px solid rgba(255,255,255,.09); border-radius:5px; font-size:10px; font-weight:500; }
        .build-tool span { font-size:9px; }
        .build-tool small { color:rgba(255,255,255,.2); font-size:8px; }
        .build-sidebar-foot { margin:18px 5px 0; padding-top:12px; border-top:1px solid rgba(255,255,255,.055); color:rgba(255,255,255,.22); font-size:8px; }
        .build-sidebar-foot i, .build-canvas-top i { display:inline-block; width:5px; height:5px; margin-right:5px; border-radius:50%; background:rgba(111,220,166,.9); box-shadow:0 0 0 3px rgba(111,220,166,.07); }
        .build-sidebar-foot span { margin:0 3px; color:rgba(255,255,255,.12); }
        .build-canvas-polished { min-height:330px; background:#08090a; }
        .build-canvas-polished .build-canvas-grid { position:absolute; inset:0; opacity:.42; background-image:linear-gradient(rgba(255,255,255,.025) 1px,transparent 1px),linear-gradient(90deg,rgba(255,255,255,.025) 1px,transparent 1px); background-size:22px 22px; mask-image:linear-gradient(to bottom,black,transparent 92%); }
        .build-canvas-polished .build-canvas-top { position:relative; z-index:2; display:flex; justify-content:space-between; padding:15px 17px; color:rgba(255,255,255,.25); font-size:8px; letter-spacing:.1em; }
        .build-canvas-polished .build-canvas-top span:last-child { letter-spacing:0; color:rgba(111,220,166,.58); }
        .build-mini-toolbar { position:absolute; z-index:3; right:14px; top:13px; display:flex; align-items:center; gap:7px; padding:4px 6px; border:1px solid rgba(255,255,255,.075); border-radius:6px; background:rgba(8,9,10,.82); color:rgba(255,255,255,.3); font-size:8px; }
        .build-mini-toolbar span:not(.build-toolbar-divider) { padding:2px 3px; }
        .build-mini-toolbar strong { color:rgba(255,255,255,.52); font-size:8px; font-weight:500; }
        .build-toolbar-divider { width:1px; height:12px; background:rgba(255,255,255,.07); }
        .build-flow-polished { position:relative; z-index:2; width:min(350px,calc(100% - 54px)); margin:17px auto 0; }
        .build-flow-node { grid-template-columns:29px 1fr auto; min-height:57px; padding:9px 10px; border-radius:8px; background:rgba(14,15,16,.94); box-shadow:0 10px 26px rgba(0,0,0,.25); }
        .build-flow-node.selected { border-color:rgba(112,174,255,.3); box-shadow:0 0 0 1px rgba(112,174,255,.07),0 14px 30px rgba(0,0,0,.28); }
        .build-node-icon { display:grid; place-items:center; width:27px; height:27px; border:1px solid rgba(255,255,255,.1); border-radius:6px; color:rgba(255,255,255,.7); font-size:10px; }
        .build-flow-node.trigger .build-node-icon { color:rgba(112,174,255,.85); border-color:rgba(112,174,255,.2); background:rgba(112,174,255,.05); }
        .build-flow-node.condition .build-node-icon { color:rgba(244,190,91,.85); border-color:rgba(244,190,91,.18); background:rgba(244,190,91,.045); }
        .build-flow-node.action .build-node-icon { color:rgba(111,220,166,.85); border-color:rgba(111,220,166,.18); background:rgba(111,220,166,.045); }
        .build-flow-node.notify .build-node-icon { color:rgba(201,163,255,.85); border-color:rgba(201,163,255,.18); background:rgba(201,163,255,.045); }
        .build-node-copy small,.build-node-copy strong,.build-node-copy em { display:block; }
        .build-node-copy small { color:rgba(255,255,255,.24); font-size:7px; letter-spacing:.12em; }
        .build-node-copy strong { margin-top:3px; color:rgba(255,255,255,.78); font-size:10px; font-weight:560; }
        .build-node-copy em { margin-top:2px; color:rgba(255,255,255,.27); font-size:8px; font-style:normal; }
        .build-node-arrow { color:rgba(255,255,255,.18); font-size:8px; letter-spacing:2px; }
        .build-flow-polished .build-flow-connector { height:25px; }
        .build-flow-polished .build-flow-connector i { background:linear-gradient(to bottom,rgba(255,255,255,.12),rgba(111,174,255,.28)); }
        .build-canvas-foot-polished { left:17px; right:17px; bottom:13px; justify-content:space-between; color:rgba(255,255,255,.22); }
        .build-canvas-foot-polished span:first-child { color:rgba(111,220,166,.58); }
        .build-canvas-foot-polished i { background:rgba(111,220,166,.9); box-shadow:0 0 0 3px rgba(111,220,166,.07); }

        .flowops-workflow-builder-section {
          position: relative;
          overflow: hidden;
          width: min(1400px, calc(100% - 128px));
          margin: 20px auto;
          padding: clamp(110px, 11vw, 170px) 0 !important;
          border-top: 1px solid rgba(255,255,255,.055);
          border-bottom: 1px solid rgba(255,255,255,.055);
        }

        .workflow-builder-intro {
          width: min(820px, 100%);
          margin: 0 auto;
          text-align: center;
        }

        .workflow-builder-intro h2 {
          margin-top: 18px !important;
          font-size: clamp(42px, 4.4vw, 64px) !important;
          line-height: .98 !important;
          letter-spacing: -.06em !important;
          text-wrap: balance;
        }

        .workflow-builder-intro p {
          max-width: 690px;
          margin: 20px auto 0 !important;
          color: rgba(255,255,255,.44) !important;
          font-size: 15px !important;
          line-height: 1.7;
        }

        .workflow-builder-shell-wrap { margin-top:72px; }
        .workflow-builder-live-demo { opacity:0; transform:translateY(18px); transition:opacity 700ms ease, transform 700ms ease; }
        .workflow-builder-live-demo.is-visible { opacity:1; transform:none; }
        .workflow-builder-live-demo.is-complete .workflow-builder-toolbar { box-shadow:inset 0 -1px 0 rgba(111,220,166,.06); }
        .builder-live-complete { color:rgba(111,220,166,.72); }
        .builder-live-complete i { background:rgba(111,220,166,.9) !important; box-shadow:0 0 0 3px rgba(111,220,166,.07) !important; }
        .palette-item-live { transition:background 220ms ease,border-color 220ms ease,transform 220ms ease,color 220ms ease; }
        .palette-item-live.is-current { border-color:rgba(255,255,255,.14); background:rgba(255,255,255,.035); transform:translateX(2px); }
        .palette-item-live.is-built > span:last-child { color:rgba(111,220,166,.72); }
        .palette-item-live.is-built b { border-color:rgba(111,220,166,.18); color:rgba(111,220,166,.82); }
        .workflow-builder-canvas { isolation:isolate; }
        .builder-canvas-actions { position:absolute; top:16px; right:18px; z-index:4; display:flex; align-items:center; gap:6px; color:rgba(255,255,255,.3); font-size:9px; }
        .builder-canvas-actions button { height:26px; min-width:26px; padding:0 7px; border:1px solid rgba(255,255,255,.08); border-radius:5px; background:rgba(255,255,255,.025); color:rgba(255,255,255,.45); font-size:9px; }
        .builder-canvas-actions i { width:1px; height:18px; margin:0 4px; background:rgba(255,255,255,.08); }
        .workflow-node-stack-live { margin-top:74px; }
        .workflow-live-unit { position:relative; }
        .workflow-node-card-live { opacity:0; transform:translateY(-12px) scale(.985); transition:opacity 430ms ease, transform 430ms cubic-bezier(.2,.75,.25,1), border-color 260ms ease, box-shadow 260ms ease; }
        .workflow-node-card-live.is-built { opacity:1; transform:none; }
        .workflow-node-card-live.is-current { border-color:rgba(111,174,255,.28); box-shadow:0 18px 42px rgba(0,0,0,.3),0 0 0 1px rgba(111,174,255,.05); }
        .workflow-node-card-live.is-built .workflow-node-icon { border-color:rgba(255,255,255,.13); }
        .workflow-node-pulse { position:absolute; inset:-1px; border:1px solid rgba(111,174,255,.42); border-radius:10px; animation:workflowNodePulse 1.15s ease-out infinite; pointer-events:none; }
        .workflow-node-link-live { height:38px; }
        .workflow-node-link-live i { height:0; opacity:0; transition:height 360ms ease,opacity 200ms ease; }
        .workflow-node-link-live.is-built i { height:100%; opacity:1; }
        .workflow-builder-complete { position:absolute; right:20px; bottom:18px; display:flex; align-items:center; gap:16px; opacity:0; transform:translateY(5px); transition:opacity 450ms ease,transform 450ms ease; color:rgba(255,255,255,.25); font-size:9px; }
        .workflow-builder-complete.show { opacity:1; transform:none; }
        .workflow-builder-complete span { display:inline-flex; align-items:center; gap:7px; color:rgba(111,220,166,.68); }
        .workflow-builder-complete span i { width:5px; height:5px; border-radius:50%; background:rgba(111,220,166,.95); box-shadow:0 0 0 3px rgba(111,220,166,.07); }
        @keyframes workflowNodePulse { 0% { opacity:.75; transform:scale(1); } 100% { opacity:0; transform:scale(1.025); } }

        .workflow-builder-shell {
          position: relative;
          margin-top: 72px;
          overflow: hidden;
          border: 1px solid rgba(255,255,255,.10);
          border-radius: 18px;
          background: #080808;
          box-shadow: 0 34px 90px rgba(0,0,0,.42);
        }

        .workflow-builder-toolbar {
          display: flex;
          align-items: center;
          justify-content: space-between;
          min-height: 58px;
          padding: 0 18px;
          border-bottom: 1px solid rgba(255,255,255,.075);
          background: rgba(255,255,255,.018);
        }

        .workflow-builder-toolbar-left,
        .workflow-builder-toolbar-actions {
          display: flex;
          align-items: center;
          gap: 12px;
        }

        .workflow-builder-toolbar-actions {
          color: rgba(255,255,255,.32);
          font-size: 10px;
        }

        .workflow-builder-toolbar-actions button {
          display: inline-flex;
          align-items: center;
          gap: 7px;
          min-height: 32px;
          padding: 0 11px;
          border: 1px solid rgba(255,255,255,.12);
          border-radius: 6px;
          background: rgba(255,255,255,.045);
          color: rgba(255,255,255,.72);
          font-size: 11px;
          cursor: pointer;
        }

        .builder-live {
          display: inline-flex;
          align-items: center;
          gap: 7px;
          color: rgba(255,255,255,.68);
          font-size: 11px;
        }

        .builder-live i,
        .workflow-builder-status i,
        .palette-connection i {
          width: 5px;
          height: 5px;
          border-radius: 50%;
          background: rgba(255,255,255,.7);
          box-shadow: 0 0 0 3px rgba(255,255,255,.055);
        }

        .builder-divider {
          width: 1px;
          height: 18px;
          background: rgba(255,255,255,.08);
        }

        .builder-context {
          color: rgba(255,255,255,.42);
          font-size: 11px;
          letter-spacing: .01em;
        }

        .workflow-builder-body {
          display: grid;
          grid-template-columns: 215px minmax(0, 1fr) 245px;
          min-height: 620px;
        }

        .workflow-builder-palette,
        .workflow-builder-inspector {
          padding: 22px 17px;
          background: rgba(255,255,255,.012);
        }

        .workflow-builder-palette {
          border-right: 1px solid rgba(255,255,255,.065);
        }

        .workflow-builder-inspector {
          border-left: 1px solid rgba(255,255,255,.065);
        }

        .palette-label {
          display: block;
          color: rgba(255,255,255,.25);
          font-size: 9px;
          font-weight: 650;
          letter-spacing: .13em;
        }

        .palette-item {
          display: grid;
          grid-template-columns: 27px 1fr auto;
          align-items: center;
          gap: 8px;
          min-height: 56px;
          margin-top: 8px;
          padding: 0 9px;
          border: 1px solid transparent;
          border-radius: 8px;
          color: rgba(255,255,255,.58);
        }

        .palette-item:hover {
          border-color: rgba(255,255,255,.08);
          background: rgba(255,255,255,.025);
        }

        .palette-item b {
          display: grid;
          place-items: center;
          width: 25px;
          height: 25px;
          border: 1px solid rgba(255,255,255,.10);
          border-radius: 6px;
          color: rgba(255,255,255,.7);
          font-size: 12px;
          font-weight: 500;
        }

        .palette-item strong,
        .palette-item small {
          display: block;
        }

        .palette-item strong {
          color: rgba(255,255,255,.72);
          font-size: 11px;
          font-weight: 550;
        }

        .palette-item small {
          margin-top: 3px;
          color: rgba(255,255,255,.28);
          font-size: 9px;
        }

        .palette-item > span:last-child {
          color: rgba(255,255,255,.24);
          font-size: 16px;
        }

        .palette-divider,
        .inspector-divider {
          height: 1px;
          margin: 22px 0;
          background: rgba(255,255,255,.065);
        }

        .palette-connection {
          display: flex;
          align-items: center;
          gap: 9px;
          margin-top: 14px;
          color: rgba(255,255,255,.38);
          font-size: 10px;
        }

        .workflow-builder-canvas {
          position: relative;
          min-width: 0;
          overflow: hidden;
          background: #070707;
        }

        .builder-canvas-grid {
          position: absolute;
          inset: 0;
          opacity: .42;
          background-image:
            linear-gradient(rgba(255,255,255,.028) 1px, transparent 1px),
            linear-gradient(90deg, rgba(255,255,255,.028) 1px, transparent 1px);
          background-size: 28px 28px;
          mask-image: linear-gradient(to bottom, black, transparent 92%);
        }

        .builder-canvas-label {
          position: absolute;
          top: 20px;
          left: 24px;
          color: rgba(255,255,255,.22);
          font-size: 9px;
          letter-spacing: .12em;
        }

        .workflow-node-stack {
          position: relative;
          z-index: 1;
          width: min(440px, calc(100% - 80px));
          margin: 74px auto 0;
        }

        .workflow-node-card {
          position: relative;
          display: grid;
          grid-template-columns: 34px 1fr auto;
          align-items: center;
          gap: 12px;
          min-height: 76px;
          padding: 12px 13px;
          border: 1px solid rgba(255,255,255,.095);
          border-radius: 10px;
          background: rgba(12,12,12,.96);
          box-shadow: 0 16px 34px rgba(0,0,0,.22);
          transition: border-color 180ms ease, transform 180ms ease;
        }

        .workflow-node-card.selected {
          border-color: rgba(255,255,255,.22);
          box-shadow: 0 18px 40px rgba(0,0,0,.28), inset 0 0 0 1px rgba(255,255,255,.025);
        }

        .workflow-node-card:hover {
          transform: translateY(-1px);
          border-color: rgba(255,255,255,.18);
        }

        .workflow-node-icon {
          display: grid;
          place-items: center;
          width: 31px;
          height: 31px;
          border: 1px solid rgba(255,255,255,.10);
          border-radius: 7px;
          color: rgba(255,255,255,.72);
          font-size: 12px;
        }

        .workflow-node-card small,
        .workflow-node-card strong,
        .workflow-node-card em {
          display: block;
        }

        .workflow-node-card small {
          color: rgba(255,255,255,.28);
          font-size: 8px;
          letter-spacing: .12em;
        }

        .workflow-node-card strong {
          margin-top: 5px;
          color: rgba(255,255,255,.78);
          font-size: 12px;
          font-weight: 560;
        }

        .workflow-node-card em {
          margin-top: 3px;
          color: rgba(255,255,255,.29);
          font-size: 9px;
          font-style: normal;
        }

        .workflow-node-menu {
          align-self: start;
          color: rgba(255,255,255,.22);
          font-size: 9px;
          letter-spacing: 2px;
        }

        .workflow-node-link {
          display: flex;
          justify-content: center;
          height: 38px;
        }

        .workflow-node-link i {
          position: relative;
          width: 1px;
          height: 100%;
          background: rgba(255,255,255,.13);
        }

        .workflow-node-link i::after {
          content: "";
          position: absolute;
          bottom: 0;
          left: 50%;
          width: 5px;
          height: 5px;
          border-right: 1px solid rgba(255,255,255,.22);
          border-bottom: 1px solid rgba(255,255,255,.22);
          transform: translate(-50%, 1px) rotate(45deg);
        }

        .workflow-branch {
          position: relative;
          margin-left: 34px;
          padding-left: 28px;
          border-left: 1px solid rgba(255,255,255,.10);
        }

        .workflow-branch-line {
          position: absolute;
          top: 38px;
          left: -1px;
          width: 29px;
          height: 1px;
          background: rgba(255,255,255,.10);
        }

        .workflow-branch-label {
          position: absolute;
          top: 26px;
          left: 9px;
          padding: 2px 5px;
          border: 1px solid rgba(255,255,255,.08);
          border-radius: 4px;
          background: #070707;
          color: rgba(255,255,255,.28);
          font-size: 8px;
        }

        .workflow-builder-status {
          position: absolute;
          right: 20px;
          bottom: 18px;
          display: flex;
          align-items: center;
          gap: 16px;
          color: rgba(255,255,255,.28);
          font-size: 9px;
        }

        .workflow-builder-status span:first-child {
          display: inline-flex;
          align-items: center;
          gap: 7px;
          color: rgba(255,255,255,.48);
        }

        .inspector-title {
          display: grid;
          grid-template-columns: 30px 1fr;
          align-items: center;
          gap: 9px;
          margin-top: 16px;
          padding-bottom: 18px;
          border-bottom: 1px solid rgba(255,255,255,.065);
        }

        .inspector-title > span {
          display: grid;
          place-items: center;
          width: 29px;
          height: 29px;
          border: 1px solid rgba(255,255,255,.10);
          border-radius: 6px;
          color: rgba(255,255,255,.68);
          font-size: 11px;
        }

        .inspector-title small,
        .inspector-title strong {
          display: block;
        }

        .inspector-title small {
          color: rgba(255,255,255,.25);
          font-size: 8px;
          letter-spacing: .12em;
        }

        .inspector-title strong {
          margin-top: 4px;
          color: rgba(255,255,255,.72);
          font-size: 11px;
          font-weight: 550;
        }

        .workflow-builder-inspector label {
          display: block;
          margin-top: 18px;
          margin-bottom: 7px;
          color: rgba(255,255,255,.27);
          font-size: 9px;
        }

        .inspector-field {
          display: flex;
          align-items: center;
          justify-content: space-between;
          min-height: 34px;
          padding: 0 9px;
          border: 1px solid rgba(255,255,255,.08);
          border-radius: 6px;
          background: rgba(255,255,255,.025);
          color: rgba(255,255,255,.52);
          font-size: 10px;
        }

        .inspector-field span {
          color: rgba(255,255,255,.22);
        }

        .inspector-toggle {
          display: flex;
          align-items: center;
          justify-content: space-between;
          margin-top: 18px;
          color: rgba(255,255,255,.42);
          font-size: 10px;
        }

        .inspector-toggle i {
          width: 25px;
          height: 15px;
          border-radius: 999px;
          background: rgba(255,255,255,.16);
          box-shadow: inset 0 0 0 1px rgba(255,255,255,.06);
        }

        .inspector-toggle i::after {
          content: "";
          display: block;
          width: 11px;
          height: 11px;
          margin: 2px;
          border-radius: 50%;
          background: rgba(255,255,255,.7);
        }

        .inspector-note span {
          color: rgba(255,255,255,.28);
          font-size: 9px;
          letter-spacing: .08em;
          text-transform: uppercase;
        }

        .inspector-note p {
          margin-top: 8px;
          color: rgba(255,255,255,.36);
          font-size: 10px;
          line-height: 1.65;
        }

        @media (max-width: 980px) {
          .flowops-workflow-builder-section {
            width: calc(100% - 40px);
          }

          .workflow-builder-body {
            grid-template-columns: 170px minmax(0, 1fr);
          }

          .workflow-builder-inspector {
            display: none;
          }
        }

        @media (max-width: 640px) {
          .flowops-workflow-builder-section {
            width: calc(100% - 32px);
            padding: 88px 0 !important;
          }

          .workflow-builder-shell {
            margin-top: 48px;
            border-radius: 14px;
          }

          .workflow-builder-toolbar {
            min-height: 52px;
            padding: 0 12px;
          }

          .workflow-builder-toolbar-actions > span {
            display: none;
          }

          .workflow-builder-body {
            grid-template-columns: 1fr;
          }

          .workflow-builder-palette {
            display: none;
          }

          .workflow-builder-canvas {
            min-height: 560px;
          }

          .workflow-node-stack {
            width: calc(100% - 36px);
          }

          .workflow-builder-status {
            left: 16px;
            right: auto;
            gap: 10px;
          }
        }

        /* ---- Vantis AI / investigation composer ---- */
        .flowops-builder-section {
          position: relative;
          overflow: hidden;
          padding: clamp(120px, 12vw, 190px) 24px clamp(116px, 10vw, 164px) !important;
          border-top: 1px solid rgba(255,255,255,.055);
          border-bottom: 1px solid rgba(255,255,255,.055);
        }

        .builder-glow {
          position: absolute;
          width: 720px;
          height: 420px;
          left: 50%;
          top: 18%;
          transform: translateX(-50%);
          pointer-events: none;
          background: radial-gradient(ellipse, rgba(90,105,255,.08) 0%, rgba(90,105,255,.025) 38%, transparent 72%);
          filter: blur(24px);
          opacity: .8;
        }

        .builder-intro {
          position: relative;
          z-index: 1;
          max-width: 820px;
          margin: 0 auto;
          text-align: center;
        }

        .builder-intro h2 {
          margin-top: 18px !important;
          font-size: clamp(42px, 4.2vw, 62px) !important;
          line-height: .99 !important;
          letter-spacing: -.055em !important;
        }

        .builder-intro p {
          max-width: 700px !important;
          margin: 20px auto 0 !important;
          font-size: clamp(15px, 1.3vw, 18px) !important;
          line-height: 1.6 !important;
          color: rgba(255,255,255,.5) !important;
        }

        .builder-shell {
          position: relative;
          z-index: 2;
          width: min(920px, 100%);
          margin: 54px auto 0;
          overflow: hidden;
          border: 1px solid rgba(255,255,255,.09);
          border-radius: 12px;
          background: linear-gradient(180deg, rgba(19,19,20,.96), rgba(10,10,11,.98));
          box-shadow: 0 30px 100px rgba(0,0,0,.35), inset 0 1px 0 rgba(255,255,255,.035);
        }

        .builder-toolbar {
          min-height: 48px;
          padding: 0 18px;
          display: flex;
          align-items: center;
          justify-content: space-between;
          border-bottom: 1px solid rgba(255,255,255,.065);
          background: rgba(255,255,255,.018);
          color: rgba(255,255,255,.42);
          font-size: 11px;
        }

        .builder-toolbar-left,
        .builder-scope,
        .builder-composer-bottom {
          display: flex;
          align-items: center;
        }

        .builder-toolbar-left { gap: 10px; }

        .builder-live {
          display: inline-flex;
          align-items: center;
          gap: 6px;
          color: rgba(255,255,255,.68);
        }

        .builder-live i,
        .preview-status i {
          width: 6px;
          height: 6px;
          border-radius: 50%;
          background: #72d9a1;
          box-shadow: 0 0 10px rgba(114,217,161,.45);
          animation: builder-pulse 2s ease-in-out infinite;
        }

        .builder-divider {
          width: 1px;
          height: 13px;
          background: rgba(255,255,255,.1);
        }

        .builder-context { color: rgba(255,255,255,.34); }

        .builder-step {
          color: rgba(255,255,255,.32);
          letter-spacing: .08em;
        }

        .builder-step b {
          margin: 0 5px;
          color: rgba(255,255,255,.15);
        }

        .builder-systems {
          display: flex;
          align-items: center;
          justify-content: center;
          flex-wrap: wrap;
          gap: 8px;
          padding: 26px 26px 20px;
        }

        .builder-system {
          display: inline-flex;
          align-items: center;
          gap: 7px;
          height: 30px;
          padding: 0 10px;
          border: 1px solid rgba(255,255,255,.07);
          border-radius: 6px;
          background: rgba(255,255,255,.028);
          color: rgba(255,255,255,.55);
          font-size: 11px;
          transition: border-color .3s ease, background .3s ease, transform .3s ease;
        }

        .builder-system:hover {
          transform: translateY(-2px);
          border-color: rgba(255,255,255,.14);
          background: rgba(255,255,255,.045);
        }

        .builder-system img {
          width: 14px;
          height: 14px;
          opacity: .78;
        }

        .builder-composer {
          margin: 0 26px;
          padding: 17px 18px 14px;
          border: 1px solid rgba(255,255,255,.085);
          border-radius: 9px;
          background: rgba(0,0,0,.25);
          box-shadow: inset 0 1px 0 rgba(255,255,255,.02);
        }

        .builder-composer-top,
        .builder-preview-head {
          display: flex;
          align-items: center;
          justify-content: space-between;
        }

        .builder-label,
        .builder-count,
        .builder-preview-head > span:first-child {
          font-size: 9px;
          letter-spacing: .1em;
          font-weight: 650;
          color: rgba(255,255,255,.3);
        }

        .builder-typewriter {
          position: relative;
        }

        .typewriter-text {
          display: inline;
        }

        .typewriter-cursor {
          display: inline-block;
          width: 1px;
          height: 1.05em;
          margin-left: 3px;
          vertical-align: -0.16em;
          background: rgba(255,255,255,.78);
          animation: typewriter-blink .85s steps(1, end) infinite;
        }

        @keyframes typewriter-blink {
          0%, 48% { opacity: 1; }
          49%, 100% { opacity: 0; }
        }

        .builder-prompt {
          min-height: 78px;
          margin: 14px 0 12px;
          color: rgba(255,255,255,.8);
          font-size: 14px;
          line-height: 1.65;
          letter-spacing: -.01em;
        }

        .builder-composer-bottom {
          justify-content: space-between;
          gap: 14px;
        }

        .builder-scope {
          gap: 7px;
          color: rgba(255,255,255,.32);
          font-size: 10px;
        }

        .builder-scope span:first-child {
          color: rgba(255,255,255,.5);
          font-size: 15px;
        }

        .builder-generate {
          height: 36px;
          display: inline-flex;
          align-items: center;
          gap: 8px;
          padding: 0 13px;
          border: 0;
          border-radius: 6px;
          background: #f4f4f2;
          color: #111;
          font-size: 12px;
          font-weight: 650;
          cursor: pointer;
          transition: transform .25s ease, background .25s ease;
        }

        .builder-generate:hover {
          transform: translateY(-2px);
          background: #fff;
        }

        .builder-preview {
          margin-top: 18px;
          padding: 18px 26px 24px;
          border-top: 1px solid rgba(255,255,255,.055);
          background: rgba(255,255,255,.012);
        }

        .preview-status {
          display: inline-flex;
          align-items: center;
          gap: 6px;
          color: rgba(255,255,255,.4);
          font-size: 10px;
        }

        .builder-actions {
          display: grid;
          grid-template-columns: repeat(3, 1fr);
          gap: 10px;
          margin-top: 14px;
        }

        .builder-action {
          min-height: 86px;
          display: grid;
          grid-template-columns: 28px 1fr;
          gap: 10px;
          padding: 14px;
          border: 1px solid rgba(255,255,255,.055);
          border-radius: 7px;
          background: rgba(255,255,255,.018);
        }

        .action-index {
          color: rgba(255,255,255,.2);
          font-size: 10px;
          font-variant-numeric: tabular-nums;
        }

        .builder-action b {
          display: block;
          color: rgba(255,255,255,.66);
          font-size: 11px;
          font-weight: 550;
        }

        .builder-action small {
          display: block;
          margin-top: 6px;
          color: rgba(255,255,255,.3);
          font-size: 10px;
          line-height: 1.45;
        }

        @keyframes builder-pulse {
          0%, 100% { opacity: .45; transform: scale(.9); }
          50% { opacity: 1; transform: scale(1.15); }
        }

        @media (max-width: 760px) {
          .flowops-builder-section { padding-inline: 18px !important; }
          .builder-shell { margin-top: 42px; }
          .builder-systems { padding: 20px 16px 16px; }
          .builder-composer { margin-inline: 16px; }
          .builder-preview { padding-inline: 16px; }
          .builder-actions { grid-template-columns: 1fr; }
        }

        @media (max-width: 520px) {
          .builder-toolbar { padding-inline: 13px; }
          .builder-context { display: none; }
          .builder-composer { margin-inline: 12px; }
          .builder-prompt { min-height: 104px; font-size: 13px; }
          .builder-composer-bottom { align-items: flex-end; }
          .builder-scope { flex-wrap: wrap; }
          .builder-generate { flex: 0 0 auto; }
        }


        /* ---- Reliability layer positioning ---- */
        .flowops-layer-section {
          position: relative;
          overflow: hidden;
          padding: clamp(116px, 11vw, 176px) 24px !important;
          border-top: 1px solid rgba(255,255,255,.055);
        }

        .layer-intro {
          width: min(760px, 100%);
          margin: 0 auto;
          text-align: center;
        }

        .layer-intro .eyebrow {
          justify-content: center;
        }

        .layer-intro h2 {
          margin-top: 18px !important;
          font-size: clamp(42px, 4.6vw, 66px) !important;
          line-height: .95 !important;
          letter-spacing: -.06em !important;
        }

        .layer-intro h2 em {
          font-style: normal;
          color: rgba(255,255,255,.48);
        }

        .layer-intro p {
          width: min(670px, 100%);
          margin: 20px auto 0 !important;
          color: rgba(255,255,255,.44) !important;
          font-size: 15px !important;
          line-height: 1.65 !important;
        }

        .layer-diagram {
          width: min(940px, 100%);
          margin: 58px auto 0;
        }

        .layer-row {
          display: grid;
          grid-template-columns: 130px repeat(4, 1fr);
          gap: 8px;
          align-items: center;
        }

        .layer-label {
          color: rgba(255,255,255,.22);
          font-size: 8px;
          letter-spacing: .11em;
        }

        .layer-chip {
          height: 52px;
          display: flex;
          align-items: center;
          justify-content: center;
          border: 1px solid rgba(255,255,255,.07);
          border-radius: 7px;
          background: rgba(255,255,255,.018);
          color: rgba(255,255,255,.5);
          font-size: 11px;
          transition: transform .3s ease, border-color .3s ease, background .3s ease;
        }

        .layer-chip:hover {
          transform: translateY(-2px);
          border-color: rgba(255,255,255,.14);
          background: rgba(255,255,255,.035);
        }

        .layer-connector {
          height: 42px;
          margin-left: 130px;
          display: flex;
          align-items: center;
          justify-content: space-around;
          position: relative;
        }

        .layer-connector::before {
          content: "";
          position: absolute;
          left: 12%;
          right: 12%;
          top: 50%;
          height: 1px;
          background: rgba(255,255,255,.08);
        }

        .layer-connector i {
          position: relative;
          z-index: 1;
          width: 4px;
          height: 4px;
          border-radius: 50%;
          background: rgba(255,255,255,.3);
          animation: layer-flow 2.6s ease-in-out infinite;
        }

        .layer-connector i:nth-child(2) { animation-delay: .55s; }
        .layer-connector i:nth-child(3) { animation-delay: 1.1s; }

        .layer-flowops {
          min-height: 94px;
          padding: 18px 22px;
          display: flex;
          align-items: center;
          justify-content: space-between;
          gap: 20px;
          border: 1px solid rgba(255,255,255,.13);
          border-radius: 9px;
          background: linear-gradient(180deg, rgba(255,255,255,.045), rgba(255,255,255,.018));
          box-shadow: 0 18px 55px rgba(0,0,0,.18), inset 0 1px 0 rgba(255,255,255,.03);
        }

        .layer-flowops-brand {
          display: flex;
          flex-direction: column;
          gap: 5px;
        }

        .layer-flowops-brand span {
          color: rgba(255,255,255,.9);
          font-size: 15px;
          font-weight: 650;
          letter-spacing: -.025em;
        }

        .layer-flowops-brand b {
          color: rgba(255,255,255,.25);
          font-size: 8px;
          letter-spacing: .11em;
        }

        .layer-capabilities {
          display: grid;
          grid-template-columns: repeat(4, auto);
          gap: 8px;
        }

        .layer-capabilities span {
          padding: 8px 10px;
          border: 1px solid rgba(255,255,255,.07);
          border-radius: 5px;
          color: rgba(255,255,255,.42);
          background: rgba(255,255,255,.025);
          font-size: 9px;
        }

        @keyframes layer-flow {
          0%, 100% { opacity: .18; transform: scale(.8); }
          40% { opacity: .8; transform: scale(1.15); }
          70% { opacity: .15; transform: scale(.8); }
        }

        /* ---- Incident journey ---- */
        .flowops-journey-section {
          padding: clamp(112px, 11vw, 170px) 24px !important;
          border-top: 1px solid rgba(255,255,255,.055);
        }

        .journey-intro {
          width: min(820px, 100%);
          margin: 0 auto;
          text-align: center;
        }

        .journey-intro .eyebrow {
          justify-content: center;
        }

        .journey-intro h2 {
          margin-top: 18px !important;
          font-size: clamp(42px, 4.5vw, 64px) !important;
          line-height: .97 !important;
          letter-spacing: -.06em !important;
        }

        .journey-intro p {
          margin: 19px auto 0 !important;
          color: rgba(255,255,255,.43) !important;
          font-size: 15px !important;
          line-height: 1.6 !important;
        }

        .journey-timeline {
          width: min(1050px, 100%);
          margin: 68px auto 0;
          position: relative;
          display: grid;
          grid-template-columns: repeat(4, 1fr);
        }

        .journey-line {
          position: absolute;
          left: 7%;
          right: 7%;
          top: 15px;
          height: 1px;
          background: rgba(255,255,255,.09);
        }

        .journey-step {
          position: relative;
          padding: 0 20px;
        }

        .journey-number {
          display: block;
          margin-bottom: 17px;
          color: rgba(255,255,255,.22);
          font-size: 9px;
          font-variant-numeric: tabular-nums;
        }

        .journey-node {
          width: 9px;
          height: 9px;
          margin-left: 0;
          position: relative;
          z-index: 1;
          border: 2px solid #101112;
          border-radius: 50%;
          background: rgba(255,255,255,.6);
          box-shadow: 0 0 0 1px rgba(255,255,255,.14);
        }

        .journey-step:nth-child(3) .journey-node { background: rgba(246,181,72,.85); }
        .journey-step:nth-child(5) .journey-node { background: rgba(114,217,161,.85); }

        .journey-copy {
          padding-top: 21px;
        }

        .journey-copy > span {
          color: rgba(255,255,255,.25);
          font-size: 8px;
          letter-spacing: .1em;
        }

        .journey-copy h3 {
          margin-top: 10px;
          color: rgba(255,255,255,.78);
          font-size: 14px;
          line-height: 1.25;
          font-weight: 550;
          letter-spacing: -.02em;
        }

        .journey-copy p {
          max-width: 205px;
          margin-top: 9px;
          color: rgba(255,255,255,.34);
          font-size: 10px;
          line-height: 1.6;
        }

        .journey-copy p b {
          color: rgba(255,255,255,.65);
          font-weight: 550;
        }

        /* ---- Operational outcomes ---- */
        .flowops-outcomes-section {
          padding: clamp(110px, 10vw, 154px) 24px !important;
          border-top: 1px solid rgba(255,255,255,.055);
        }

        .outcomes-intro {
          width: min(700px, 100%);
          margin: 0 auto;
          text-align: center;
        }

        .outcomes-intro .eyebrow {
          justify-content: center;
        }

        .outcomes-intro h2 {
          margin-top: 18px !important;
          font-size: clamp(42px, 4.5vw, 62px) !important;
          line-height: .96 !important;
          letter-spacing: -.06em !important;
        }

        .outcomes-intro h2 em {
          font-style: normal;
          color: rgba(255,255,255,.47);
        }

        .outcomes-intro p {
          margin: 19px auto 0 !important;
          color: rgba(255,255,255,.42) !important;
          font-size: 14px !important;
          line-height: 1.65 !important;
        }

        .outcomes-grid {
          width: min(980px, 100%);
          margin: 55px auto 0;
          display: grid;
          grid-template-columns: repeat(4, 1fr);
          border-top: 1px solid rgba(255,255,255,.075);
          border-bottom: 1px solid rgba(255,255,255,.075);
        }

        .outcome-card {
          min-height: 155px;
          padding: 22px 23px;
        }

        .outcome-card + .outcome-card {
          border-left: 1px solid rgba(255,255,255,.075);
        }

        .outcome-card > span {
          color: rgba(255,255,255,.2);
          font-size: 9px;
        }

        .outcome-card strong {
          display: block;
          margin-top: 30px;
          color: rgba(255,255,255,.72);
          font-size: 14px;
          font-weight: 550;
          letter-spacing: -.02em;
        }

        .outcome-card p {
          max-width: 165px;
          margin-top: 8px;
          color: rgba(255,255,255,.31);
          font-size: 10px;
          line-height: 1.55;
        }

        @media (max-width: 800px) {
          .layer-row {
            grid-template-columns: 1fr 1fr;
          }

          .layer-label {
            grid-column: 1 / -1;
            margin-bottom: 3px;
          }

          .layer-connector {
            margin-left: 0;
          }

          .layer-flowops {
            flex-direction: column;
            align-items: flex-start;
          }

          .journey-timeline {
            grid-template-columns: 1fr 1fr;
            row-gap: 45px;
          }

          .journey-line {
            display: none;
          }

          .outcomes-grid {
            grid-template-columns: 1fr 1fr;
          }

          .outcome-card:nth-child(3) {
            border-left: 0;
            border-top: 1px solid rgba(255,255,255,.075);
          }

          .outcome-card:nth-child(4) {
            border-top: 1px solid rgba(255,255,255,.075);
          }
        }

        @media (max-width: 560px) {
          .flowops-layer-section,
          .flowops-journey-section,
          .flowops-outcomes-section {
            padding-inline: 18px !important;
          }

          .layer-diagram {
            margin-top: 42px;
          }

          .layer-capabilities {
            width: 100%;
            grid-template-columns: repeat(2, 1fr);
          }

          .layer-capabilities span {
            text-align: center;
          }

          .journey-timeline {
            grid-template-columns: 1fr;
            row-gap: 30px;
            margin-top: 48px;
          }

          .journey-step {
            padding: 0;
            display: grid;
            grid-template-columns: 32px 1fr;
            column-gap: 12px;
          }

          .journey-number {
            grid-row: span 2;
            margin: 0;
            padding-top: 3px;
          }

          .journey-node {
            position: absolute;
            left: 26px;
            top: 2px;
          }

          .journey-copy {
            padding-top: 0;
            padding-left: 18px;
          }

          .outcomes-grid {
            grid-template-columns: 1fr;
          }

          .outcome-card,
          .outcome-card:nth-child(3),
          .outcome-card:nth-child(4) {
            min-height: 125px;
            border-left: 0;
            border-top: 1px solid rgba(255,255,255,.075);
          }

          .outcome-card:first-child {
            border-top: 0;
          }

          .outcome-card strong {
            margin-top: 18px;
          }
        }

        /* ---- Integrations / reference-style stack grid ---- */
        .integrations-section {
          padding-top: clamp(118px, 11vw, 176px) !important;
          padding-bottom: clamp(112px, 10vw, 156px) !important;
          overflow: hidden;
        }

        .integrations-intro {
          max-width: 850px;
          margin-inline: auto;
          text-align: center;
        }

        .integrations-intro h2 {
          margin-top: 18px !important;
          font-size: clamp(42px, 4.2vw, 62px) !important;
          line-height: .98 !important;
          letter-spacing: -.055em !important;
        }

        .integrations-intro p {
          max-width: 760px !important;
          margin: 20px auto 0 !important;
          font-size: clamp(15px, 1.35vw, 18px) !important;
          line-height: 1.55 !important;
          color: rgba(255,255,255,.52) !important;
        }

        .integration-grid-reference {
          width: min(1110px, 100%) !important;
          margin: 56px auto 0 !important;
          display: grid !important;
          grid-template-columns: repeat(4, minmax(0, 1fr)) !important;
          gap: 14px !important;
        }

        .integration-reference-card {
          min-height: 174px;
          padding: 20px 18px 18px !important;
          border: 1px solid rgba(255,255,255,.075) !important;
          border-radius: 12px !important;
          background: linear-gradient(180deg, rgba(255,255,255,.035), rgba(255,255,255,.018)) !important;
          box-shadow: inset 0 1px 0 rgba(255,255,255,.025), 0 12px 34px rgba(0,0,0,.12) !important;
          transition: transform .35s cubic-bezier(.2,.7,.2,1), border-color .35s ease, background .35s ease !important;
        }

        .integration-reference-card:hover {
          transform: translateY(-4px) !important;
          border-color: rgba(255,255,255,.14) !important;
          background: linear-gradient(180deg, rgba(255,255,255,.055), rgba(255,255,255,.025)) !important;
        }

        .integration-card-title {
          color: rgba(255,255,255,.52);
          font-size: 10px;
          line-height: 1;
          font-weight: 650;
          letter-spacing: .085em;
          text-transform: uppercase;
        }

        .integration-card-items {
          display: grid;
          gap: 13px;
          margin-top: 20px;
        }

        .integration-item {
          display: flex;
          align-items: center;
          min-width: 0;
          gap: 9px;
          color: rgba(255,255,255,.78);
          font-size: 13px;
          line-height: 1;
        }

        .integration-item b {
          overflow: hidden;
          text-overflow: ellipsis;
          white-space: nowrap;
          font-weight: 500;
          letter-spacing: -.01em;
        }

        .integration-logo-wrap {
          width: 18px;
          height: 18px;
          flex: 0 0 18px;
          display: grid;
          place-items: center;
        }

        .integration-logo {
          width: 17px;
          height: 17px;
          display: block;
          object-fit: contain;
          opacity: .9;
          transition: opacity .25s ease, transform .25s ease;
        }

        .integration-svg-logo {
          width: 18px;
          height: 18px;
          display: block;
          opacity: .98;
          transition: opacity .25s ease, transform .25s ease;
        }

        .teams-logo {
          color: #7b83eb;
        }

        .integration-generic-icon {
          width: 17px;
          height: 17px;
          display: grid;
          place-items: center;
          color: rgba(255,255,255,.72);
          font-size: 14px;
          font-weight: 600;
          line-height: 1;
        }

        .integration-reference-card::after {
          content: '';
          display: block;
          width: 28px;
          height: 1px;
          margin-top: 18px;
          background: linear-gradient(90deg, rgba(255,255,255,.22), transparent);
          opacity: .7;
          transition: width .3s ease, opacity .3s ease;
        }

        .integration-reference-card:hover::after {
          width: 48px;
          opacity: 1;
        }

        .integration-reference-card:hover .integration-logo,
        .integration-reference-card:hover .integration-svg-logo {
          opacity: 1;
          transform: scale(1.06);
        }

        .integration-custom {
          width: fit-content;
          max-width: 100%;
          margin: 30px auto 0;
          padding: 10px 15px;
          display: inline-flex;
          align-items: center;
          justify-content: center;
          gap: 9px;
          border: 1px solid rgba(255,255,255,.075);
          border-radius: 7px;
          background: rgba(255,255,255,.025);
          color: rgba(255,255,255,.46);
          font-size: 12px;
          letter-spacing: -.01em;
        }

        .integration-link-icon {
          width: 17px;
          height: 17px;
          display: grid;
          place-items: center;
          color: rgba(255,255,255,.54);
          font-size: 13px;
        }

        @media (max-width: 1180px) {
          .integration-grid-reference {
            grid-template-columns: repeat(2, minmax(0, 1fr)) !important;
            max-width: 760px !important;
          }
        }

        @media (max-width: 760px) {
          .integration-grid-reference {
            grid-template-columns: repeat(2, minmax(0, 1fr)) !important;
            gap: 10px !important;
            margin-top: 42px !important;
          }

          .integration-reference-card {
            min-height: 150px;
            padding: 17px 15px 16px !important;
          }

          .integration-card-items {
            gap: 11px;
            margin-top: 17px;
          }
        }

        @media (max-width: 480px) {
          .integrations-intro h2 {
            font-size: 39px !important;
          }

          .integrations-intro p {
            font-size: 14px !important;
          }

          .integration-grid-reference {
            grid-template-columns: 1fr !important;
            max-width: 360px !important;
          }

          .integration-reference-card {
            min-height: auto;
          }

          .integration-custom {
            width: 100%;
            text-align: center;
          }
        }


        .hero .tabs button {
          min-width: 72px;
        }

        @media (max-width: 640px) {
          .hero .tabs {
            overflow-x: auto;
            justify-content: flex-start;
            scrollbar-width: none;
          }

          .hero .tabs::-webkit-scrollbar {
            display: none;
          }

          .hero .tabs button {
            flex: 0 0 auto;
          }
        }

        /* ---- Mobile ---- */
        @media (max-width: 900px) {
          .section,
          .wide-panel,
          .center-section,
          .faq-section,
          .carousel,
          .cta {
            width: calc(100% - 40px) !important;
          }

          .hero {
            padding: 76px 20px 88px !important;
          }

          .hero .buttons {
            margin-top: 24px !important;
            gap: 10px !important;
          }

          .hero .tabs {
            margin-top: 34px !important;
          }

          .hero .hero-window-wrap {
            margin-top: 24px !important;
          }

          .section,
          .center-section {
            padding-top: 88px !important;
            padding-bottom: 88px !important;
          }

          .wide-panel {
            padding: 28px !important;
          }

          .window-stage {
            padding: 0 !important;
            border-radius: 15px;
          }

          .window-stage[style] {
            padding: 8px !important;
            border-radius: 17px;
          }

          .hero h1 {
            font-size: clamp(46px, 13vw, 72px) !important;
          }
        }

        @media (max-width: 640px) {
          .announcement {
            padding-inline: 16px !important;
          }

          .section,
          .wide-panel,
          .center-section,
          .faq-section,
          .carousel,
          .cta {
            width: calc(100% - 32px) !important;
          }

          .window-stage {
            padding: 0 !important;
            border-radius: 15px;
          }

          .window-stage[style] {
            padding: 6px !important;
            border-radius: 16px;
          }

          .window-stage .window {
            border-radius: 12px !important;
          }

          .buttons {
            width: 100%;
          }

          .buttons > * {
            flex: 1 1 auto;
          }
        }

        @media (prefers-reduced-motion: reduce) {
          *,
          *::before,
          *::after {
            scroll-behavior: auto !important;
            animation-duration: 0.01ms !important;
            animation-iteration-count: 1 !important;
            transition-duration: 0.01ms !important;
          }
        }
        /* ---- Production-grade Build tab ---- */
        .build-panel-production {
          overflow: hidden;
          background: #080808 !important;
        }
        .build-production-header {
          height: 62px;
          padding: 0 18px;
          display: flex;
          align-items: center;
          justify-content: space-between;
          border-bottom: 1px solid rgba(255,255,255,.075);
          background: #0b0b0b;
        }
        .build-production-title { min-width: 0; }
        .build-production-crumb { display:flex; align-items:center; gap:7px; margin-bottom:4px; color:rgba(255,255,255,.23); font-size:7px; letter-spacing:.11em; white-space:nowrap; }
        .build-production-crumb i { font-style:normal; color:rgba(255,255,255,.12); }
        .build-production-title h2 { margin:0; color:rgba(255,255,255,.82); font-size:14px; line-height:1.1; font-weight:560; letter-spacing:-.025em; }
        .build-production-actions { display:flex; align-items:center; gap:12px; }
        .build-production-saved { display:flex; align-items:center; gap:6px; color:rgba(255,255,255,.32); font-size:8px; white-space:nowrap; }
        .build-production-saved b { width:5px; height:5px; border-radius:50%; background:rgba(111,220,166,.9); box-shadow:0 0 0 3px rgba(111,220,166,.06); }
        .build-production-test { height:30px; display:inline-flex; align-items:center; gap:7px; padding:0 10px; border:1px solid rgba(255,255,255,.12); border-radius:6px; background:rgba(255,255,255,.045); color:rgba(255,255,255,.68); font-size:9px; transition:background .18s ease,border-color .18s ease,transform .18s ease; }
        .build-production-test:hover { background:rgba(255,255,255,.075); border-color:rgba(255,255,255,.18); transform:translateY(-1px); }
        .build-production-body { display:grid; grid-template-columns:154px minmax(0,1fr); min-height:338px; }
        .build-production-sidebar { position:relative; display:flex; flex-direction:column; padding:14px 10px; border-right:1px solid rgba(255,255,255,.07); background:#0a0a0a; }
        .build-production-sidebar-head { height:24px; padding:0 5px 9px; display:flex; align-items:flex-start; justify-content:space-between; color:rgba(255,255,255,.25); font-size:7px; letter-spacing:.12em; }
        .build-production-sidebar-head button { width:20px; height:20px; margin-top:-3px; display:grid; place-items:center; border:1px solid rgba(255,255,255,.09); border-radius:5px; background:rgba(255,255,255,.025); color:rgba(255,255,255,.46); font-size:13px; line-height:1; }
        .build-production-step { min-height:47px; display:grid; grid-template-columns:24px minmax(0,1fr) 15px; align-items:center; gap:7px; padding:0 6px; border:1px solid transparent; border-radius:7px; color:rgba(255,255,255,.45); }
        .build-production-step.is-active { background:rgba(255,255,255,.045); border-color:rgba(255,255,255,.085); color:rgba(255,255,255,.78); }
        .build-production-step-icon { width:22px; height:22px; display:grid; place-items:center; border:1px solid rgba(255,255,255,.09); border-radius:5px; font-size:9px; }
        .build-production-step-icon.trigger { color:rgba(112,174,255,.9); border-color:rgba(112,174,255,.2); background:rgba(112,174,255,.05); }
        .build-production-step-icon.condition { color:rgba(244,190,91,.9); border-color:rgba(244,190,91,.18); background:rgba(244,190,91,.045); }
        .build-production-step-icon.action { color:rgba(111,220,166,.9); border-color:rgba(111,220,166,.18); background:rgba(111,220,166,.045); }
        .build-production-step-icon.notify { color:rgba(201,163,255,.9); border-color:rgba(201,163,255,.18); background:rgba(201,163,255,.045); }
        .build-production-step strong,.build-production-step small { display:block; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
        .build-production-step strong { font-size:9px; font-weight:540; }
        .build-production-step small { margin-top:3px; color:rgba(255,255,255,.22); font-size:7px; }
        .build-production-step em { font-style:normal; color:rgba(255,255,255,.16); font-size:7px; text-align:right; }
        .build-production-sidebar-bottom { margin-top:auto; padding:11px 5px 1px; border-top:1px solid rgba(255,255,255,.055); }
        .build-production-sidebar-bottom span,.build-production-sidebar-bottom small { display:block; }
        .build-production-sidebar-bottom span { color:rgba(255,255,255,.31); font-size:7px; }
        .build-production-sidebar-bottom i { display:inline-block; width:4px; height:4px; margin-right:5px; border-radius:50%; background:rgba(111,220,166,.85); }
        .build-production-sidebar-bottom small { margin-top:5px; color:rgba(255,255,255,.18); font-size:7px; }
        .build-production-canvas { position:relative; min-width:0; overflow:hidden; background:#090a0b; }
        .build-production-grid { position:absolute; inset:0; opacity:.55; background-image:linear-gradient(rgba(255,255,255,.022) 1px,transparent 1px),linear-gradient(90deg,rgba(255,255,255,.022) 1px,transparent 1px); background-size:24px 24px; mask-image:linear-gradient(to bottom,black 0%,black 70%,transparent 100%); }
        .build-production-canvas-head { position:absolute; top:13px; left:16px; right:16px; z-index:2; display:flex; align-items:center; justify-content:space-between; color:rgba(255,255,255,.22); font-size:7px; letter-spacing:.12em; }
        .build-production-canvas-head > div { display:flex; align-items:center; gap:8px; padding:4px 6px; border:1px solid rgba(255,255,255,.065); border-radius:5px; background:rgba(8,9,10,.86); letter-spacing:0; }
        .build-production-canvas-head > div span { color:rgba(255,255,255,.28); }
        .build-production-canvas-head > div b { color:rgba(255,255,255,.5); font-size:7px; font-weight:500; }
        .build-production-canvas-head > div i { width:1px; height:10px; background:rgba(255,255,255,.07); }
        .build-production-flow { position:absolute; z-index:1; left:28px; right:28px; top:76px; bottom:52px; display:flex; align-items:center; justify-content:center; gap:0; }
        .build-production-flow-item { display:flex; align-items:center; min-width:0; }
        .build-production-node { position:relative; width:165px; min-height:72px; display:grid; grid-template-columns:29px minmax(0,1fr) 13px; align-items:center; gap:9px; padding:10px; border:1px solid rgba(255,255,255,.095); border-radius:8px; background:#0e0f10; box-shadow:0 14px 28px rgba(0,0,0,.28),inset 0 1px 0 rgba(255,255,255,.025); transition:transform .2s ease,border-color .2s ease,box-shadow .2s ease; }
        .build-production-node:hover { transform:translateY(-2px); border-color:rgba(255,255,255,.16); }
        .build-production-node.is-selected { border-color:rgba(112,174,255,.3); box-shadow:0 0 0 1px rgba(112,174,255,.06),0 16px 30px rgba(0,0,0,.3); }
        .build-production-node-icon { width:28px; height:28px; display:grid; place-items:center; border:1px solid rgba(255,255,255,.09); border-radius:6px; font-size:10px; }
        .build-production-node-icon.trigger { color:rgba(112,174,255,.9); border-color:rgba(112,174,255,.2); background:rgba(112,174,255,.05); }
        .build-production-node-icon.condition { color:rgba(244,190,91,.9); border-color:rgba(244,190,91,.18); background:rgba(244,190,91,.045); }
        .build-production-node-icon.action { color:rgba(111,220,166,.9); border-color:rgba(111,220,166,.18); background:rgba(111,220,166,.045); }
        .build-production-node-icon.notify { color:rgba(201,163,255,.9); border-color:rgba(201,163,255,.18); background:rgba(201,163,255,.045); }
        .build-production-node-copy { min-width:0; }
        .build-production-node-copy span,.build-production-node-copy strong,.build-production-node-copy small { display:block; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
        .build-production-node-copy span { color:rgba(255,255,255,.24); font-size:6px; letter-spacing:.12em; }
        .build-production-node-copy strong { margin-top:4px; color:rgba(255,255,255,.78); font-size:9px; font-weight:560; letter-spacing:-.01em; }
        .build-production-node-copy small { margin-top:3px; color:rgba(255,255,255,.25); font-size:7px; }
        .build-production-node > button { align-self:start; margin-top:1px; padding:0; border:0; background:transparent; color:rgba(255,255,255,.17); font-size:7px; letter-spacing:1px; }
        .build-production-connector { position:relative; width:34px; height:1px; flex:none; background:rgba(255,255,255,.1); }
        .build-production-connector::after { content:""; position:absolute; right:-1px; top:-3px; width:6px; height:6px; border-top:1px solid rgba(255,255,255,.18); border-right:1px solid rgba(255,255,255,.18); transform:rotate(45deg); }
        .build-production-connector i { position:absolute; left:0; top:-1px; width:46%; height:3px; border-radius:2px; background:linear-gradient(90deg,rgba(112,174,255,.45),rgba(111,220,166,.32)); filter:blur(.3px); animation:build-flow-line 2.8s ease-in-out infinite; }
        .build-production-canvas-foot { position:absolute; z-index:2; left:16px; right:16px; bottom:13px; display:flex; justify-content:space-between; color:rgba(255,255,255,.2); font-size:7px; }
        .build-production-canvas-foot span:first-child { color:rgba(111,220,166,.58); }
        .build-production-canvas-foot i { display:inline-block; width:4px; height:4px; margin-right:5px; border-radius:50%; background:rgba(111,220,166,.9); box-shadow:0 0 0 3px rgba(111,220,166,.06); }
        .build-production-canvas-foot b { margin:0 5px; color:rgba(255,255,255,.12); font-weight:400; }
        @keyframes build-flow-line { 0%,100% { transform:translateX(0); opacity:.3; } 50% { transform:translateX(75px); opacity:.85; } }
        @media (max-width: 900px) {
          .build-production-body { grid-template-columns:130px minmax(0,1fr); }
          .build-production-node { width:145px; }
          .build-production-connector { width:20px; }
          .build-production-flow { left:14px; right:14px; }
        }
        @media (max-width: 700px) {
          .build-production-header { padding:0 13px; }
          .build-production-crumb span:first-child,.build-production-saved { display:none; }
          .build-production-body { grid-template-columns:1fr; }
          .build-production-sidebar { display:none; }
          .build-production-canvas { min-height:330px; }
          .build-production-flow { top:70px; left:18px; right:18px; overflow-x:auto; justify-content:flex-start; padding-bottom:4px; }
          .build-production-node { width:160px; }
        }
      `}
      </style>
    </>
  );
}
