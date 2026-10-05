"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { LogoMark } from "@/components/brand/logo";
import { useReducedMotion } from "@/components/motion/use-reduced-motion";

/* ------------------------------------------------------------------ */
/*  Node definitions                                                   */
/* ------------------------------------------------------------------ */

const NODES = [
  { id: "trigger", label: "TRIGGER", icon: "bolt" },
  { id: "process", label: "PROCESS", icon: "flow" },
  { id: "reliability", label: "RELIABILITY", icon: "shield" },
  { id: "completed", label: "COMPLETED", icon: "check" },
] as const;

type NodeState = "idle" | "running" | "done";

const NODE_MS = [0, 350, 700, 1050]; // stagger start per node
const DONE_MS = [300, 650, 1000, 1300]; // when each node hits 'done'
const EXIT_DELAY = 1550; // begin overlay fade-out
const REMOVE_DELAY = 1950; // remove from DOM

/* ------------------------------------------------------------------ */
/*  Tiny SVG node icons                                                */
/* ------------------------------------------------------------------ */

function NodeIcon({ icon, state }: { icon: string; state: NodeState }) {
  const color =
    state === "idle"
      ? "rgba(255,255,255,0.12)"
      : state === "running"
        ? "rgba(167,139,250,0.9)"
        : "rgba(167,139,250,1)";

  if (icon === "check") {
    // Checkmark — only visible when done
    return (
      <svg width="14" height="14" viewBox="0 0 14 14" fill="none">
        <path
          d="M3 7.5L5.5 10L11 4"
          stroke={state === "done" ? color : "rgba(255,255,255,0.12)"}
          strokeWidth="1.5"
          strokeLinecap="round"
          strokeLinejoin="round"
          style={{
            strokeDasharray: 20,
            strokeDashoffset: state === "done" ? 0 : 20,
            transition: "stroke-dashoffset 250ms ease-out, stroke 250ms ease",
          }}
        />
      </svg>
    );
  }

  if (icon === "bolt") {
    return (
      <svg width="14" height="14" viewBox="0 0 14 14" fill="none">
        <path
          d="M8 1L3 8h3.5L5.5 13 11 6H7.5L8 1z"
          fill={color}
          style={{ transition: "fill 250ms ease" }}
        />
      </svg>
    );
  }

  if (icon === "flow") {
    return (
      <svg width="14" height="14" viewBox="0 0 14 14" fill="none">
        <circle
          cx="4"
          cy="7"
          r="2"
          fill={color}
          style={{ transition: "fill 250ms ease" }}
        />
        <circle
          cx="10"
          cy="4"
          r="1.5"
          fill={color}
          style={{ transition: "fill 250ms ease" }}
        />
        <circle
          cx="10"
          cy="10"
          r="1.5"
          fill={color}
          style={{ transition: "fill 250ms ease" }}
        />
        <path
          d="M6 7h2m0-3l2-1m2 4h-2m0 3l-2 1"
          stroke={color}
          strokeWidth="0.8"
          strokeLinecap="round"
          style={{ transition: "stroke 250ms ease" }}
        />
      </svg>
    );
  }

  // shield
  return (
    <svg width="14" height="14" viewBox="0 0 14 14" fill="none">
      <path
        d="M7 1.5L2.5 3.5v3c0 3.25 4.5 5.5 4.5 5.5s4.5-2.25 4.5-5.5v-3L7 1.5z"
        fill={color}
        style={{ transition: "fill 250ms ease" }}
      />
    </svg>
  );
}

/* ------------------------------------------------------------------ */
/*  Main component                                                     */
/* ------------------------------------------------------------------ */

export function StartupLoader({ onComplete }: { onComplete: () => void }) {
  const prefersReduced = useReducedMotion();
  const [states, setStates] = useState<NodeState[]>([
    "idle",
    "idle",
    "idle",
    "idle",
  ]);
  const [exiting, setExiting] = useState(false);
  const timers = useRef<ReturnType<typeof setTimeout>[]>([]);

  const schedule = useCallback((fn: () => void, ms: number) => {
    const id = setTimeout(fn, ms);
    timers.current.push(id);
    return id;
  }, []);

  useEffect(() => {
    if (prefersReduced) {
      onComplete();
      return;
    }

    // Stagger each node: idle → running
    NODE_MS.forEach((ms, i) => {
      schedule(() => {
        setStates((prev) => prev.map((s, j) => (j === i ? "running" : s)));
      }, ms);
    });

    // Stagger each node: running → done
    DONE_MS.forEach((ms, i) => {
      schedule(() => {
        setStates((prev) => prev.map((s, j) => (j === i ? "done" : s)));
      }, ms);
    });

    // Exit fade
    schedule(() => setExiting(true), EXIT_DELAY);
    schedule(onComplete, REMOVE_DELAY);

    return () => {
      timers.current.forEach(clearTimeout);
      timers.current = [];
    };
  }, [prefersReduced, onComplete, schedule]);

  if (prefersReduced) return null;

  return (
    <div
      aria-hidden="true"
      className="fixed inset-0 z-[9999] flex flex-col items-center justify-center bg-[#050505]"
      style={{
        opacity: exiting ? 0 : 1,
        transition: "opacity 400ms ease-out",
        pointerEvents: exiting ? "none" : "auto",
      }}
    >
      {/* Logo */}
      <div
        style={{
          opacity: 0.6,
          marginBottom: 40,
        }}
      >
        <LogoMark size={32} />
      </div>

      {/* Workflow nodes */}
      <div className="relative flex items-center gap-0">
        {NODES.map((node, i) => {
          const state = states[i];
          const isLast = i === NODES.length - 1;

          return (
            <div key={node.id} className="flex items-center">
              {/* Connection line before node (except first) */}
              {i > 0 && (
                <div className="relative mx-1 h-px w-10 sm:w-14">
                  {/* Track */}
                  <div className="absolute inset-0 rounded-full bg-white/[0.06]" />
                  {/* Signal fill */}
                  <div
                    className="absolute inset-y-0 left-0 rounded-full"
                    style={{
                      background:
                        "linear-gradient(90deg, rgba(167,139,250,0.6), rgba(167,139,250,0.3))",
                      width: states[i - 1] === "done" ? "100%" : "0%",
                      transition: "width 250ms ease-out",
                    }}
                  />
                </div>
              )}

              {/* Node pill */}
              <div
                className="flex flex-col items-center gap-2"
                style={{ width: 64 }}
              >
                <div
                  className="flex h-10 w-10 items-center justify-center rounded-lg border"
                  style={{
                    borderColor:
                      state === "idle"
                        ? "rgba(255,255,255,0.06)"
                        : state === "running"
                          ? "rgba(167,139,250,0.35)"
                          : "rgba(167,139,250,0.25)",
                    background:
                      state === "idle"
                        ? "rgba(255,255,255,0.02)"
                        : state === "running"
                          ? "rgba(167,139,250,0.08)"
                          : "rgba(167,139,250,0.05)",
                    boxShadow:
                      state === "running"
                        ? "0 0 12px rgba(167,139,250,0.12)"
                        : "none",
                    transition:
                      "border-color 250ms ease, background 250ms ease, box-shadow 250ms ease",
                  }}
                >
                  <NodeIcon icon={node.icon} state={state} />
                </div>

                {/* Label */}
                <span
                  className="font-mono text-[8px] tracking-[0.16em]"
                  style={{
                    color:
                      state === "idle"
                        ? "rgba(255,255,255,0.15)"
                        : state === "running"
                          ? "rgba(167,139,250,0.7)"
                          : "rgba(167,139,250,0.5)",
                    transition: "color 250ms ease",
                  }}
                >
                  {node.label}
                </span>
              </div>
            </div>
          );
        })}
      </div>

      {/* Status text */}
      <div className="mt-8 h-4">
        <span
          className="font-mono text-[10px] tracking-[0.18em] uppercase"
          style={{
            color: "rgba(255,255,255,0.2)",
            transition: "color 300ms ease",
          }}
        >
          {states[3] === "done" ? "Workflow completed" : "Executing workflow"}
        </span>
      </div>
    </div>
  );
}
