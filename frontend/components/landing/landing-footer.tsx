"use client";

import { Logo } from "@/components/brand/logo";

export function LandingFooter() {
  return (
    <footer>
      <div>
        <Logo />
        <p>
          Reliability infrastructure for automation.
        </p>
      </div>
      <div className="footer-links">
        <div>
          <b>Platform</b>
          <a href="#platform">Overview</a>
          <a href="#platform">Monitoring</a>
        </div>
        <div>
          <b>Resources</b>
          <a href="#integrations">Docs</a>
          <a href="#">Guides</a>
        </div>
        <div>
          <b>Compare</b>
          <a href="#">vs Datadog</a>
          <a href="#">vs Sentry</a>
        </div>
        <div>
          <b>Legal</b>
          <a href="#">Privacy</a>
          <a href="#">Terms</a>
        </div>
      </div>
      <div className="copyright">
        &copy; {new Date().getFullYear()} Vantis, Inc.{" "}
        <span>
          <i /> All systems operational
        </span>
      </div>
    </footer>
  );
}
