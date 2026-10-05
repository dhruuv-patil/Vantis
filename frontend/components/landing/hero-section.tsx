"use client";

import { useEffect, useRef, useState } from "react";
import Link from "next/link";
import { ArrowRight, Play, Sparkles } from "lucide-react";
import { tabs } from "./data";
import { Reveal, Window } from "./primitives";
import { panels } from "./hero-panels";

export function HeroSection() {
  const [tab, setTab] = useState(0);
  const tabRefs = useRef<(HTMLButtonElement | null)[]>([]);
  const [indicator, setIndicator] = useState({ left: 0, width: 0 });
  const ActivePanel = panels[tab];

  useEffect(() => {
    const measure = () => {
      const el = tabRefs.current[tab];
      if (el) setIndicator({ left: el.offsetLeft, width: el.offsetWidth });
    };
    measure();
    window.addEventListener("resize", measure);
    return () => window.removeEventListener("resize", measure);
  }, [tab]);

  return (
    <section className="hero">
      <div className="hero-atmosphere" aria-hidden>
        <span className="orb orb-a" />
        <span className="orb orb-b" />
        <span className="orb orb-c" />
        <span className="grid-lines" />
        <span className="grain" />
      </div>

      <Reveal className="reveal-fade">
        <div className="eyebrow center">
          <Sparkles size={13} /> Reliability infrastructure for automation
        </div>
      </Reveal>
      <Reveal delay={60} className="reveal-fade">
        <h1>
          Vantis
          <br />
          <span>Reliability infrastructure for automation.</span>
        </h1>
      </Reveal>
      <Reveal delay={120} className="reveal-fade">
        <p>
          Build, monitor, and understand your workflows before failures become
          incidents.
        </p>
      </Reveal>
      <Reveal delay={180} className="reveal-fade">
        <div className="buttons">
          <Link href="/register" className="white-button">
            Start building <ArrowRight size={16} />
          </Link>
          <a className="outline-button" href="#platform">
            See how it works <Play size={14} />
          </a>
        </div>
      </Reveal>
      <Reveal delay={220} className="reveal-fade">
        <small>Free during private beta · No credit card required</small>
      </Reveal>

      <Reveal delay={260} className="reveal-fade">
        <div className="tabs">
          <span
            className="tab-indicator"
            style={{ left: indicator.left, width: indicator.width }}
          />
          {tabs.map((x, i) => (
            <button
              ref={(el) => {
                tabRefs.current[i] = el;
              }}
              className={tab === i ? "selected" : ""}
              onClick={() => setTab(i)}
              key={x.label}
            >
              {x.label}
            </button>
          ))}
        </div>
      </Reveal>

      <Reveal delay={300} className="reveal-fade hero-window-wrap">
        <Window>
          <div key={tab} className="panel-fade-in">
            <ActivePanel />
          </div>
        </Window>
      </Reveal>
    </section>
  );
}
