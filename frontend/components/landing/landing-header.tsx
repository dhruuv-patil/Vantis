"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { ArrowRight, Menu, X } from "lucide-react";
import { Logo } from "@/components/brand/logo";

export function LandingHeader() {
  const [mobile, setMobile] = useState(false);
  const [scrolled, setScrolled] = useState(false);

  useEffect(() => {
    const onScroll = () => setScrolled(window.scrollY > 8);
    onScroll();
    window.addEventListener("scroll", onScroll, { passive: true });
    return () => window.removeEventListener("scroll", onScroll);
  }, []);

  return (
    <>
      <div className="announcement">
        <span>NEW</span> Vantis is now in private beta{" "}
        <ArrowRight size={13} />
      </div>
      <header className={scrolled ? "is-scrolled" : ""}>
        <Logo />
        <nav>
          <a href="#workflow-builder">Builder</a>
          <a href="#platform">Platform</a>
          <a href="#integrations">Integrations</a>
          <a href="#security">Security</a>
          <a href="#faq">FAQs</a>
        </nav>
        <div className="header-actions">
          <Link href="/login">Log in</Link>
          <Link href="/register" className="white-button">
            Sign up
          </Link>
        </div>
        <button className="mobile-menu" onClick={() => setMobile(!mobile)}>
          {mobile ? <X /> : <Menu />}
        </button>
      </header>
      {mobile && (
        <div className="mobile-nav">
          <a href="#workflow-builder">Builder</a>
          <a href="#platform">Platform</a>
          <a href="#integrations">Integrations</a>
          <a href="#security">Security</a>
          <a href="#faq">FAQs</a>
        </div>
      )}
    </>
  );
}
