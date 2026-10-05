"use client";

import { useEffect, useRef, useState } from "react";
import { ArrowRight } from "lucide-react";
import { Reveal } from "./primitives";

export function WorkflowBuilderDemo() {
  const steps = [
    {
      label: "Trigger",
      title: "Stripe webhook",
      detail: "order.created",
      icon: "↗",
    },
    {
      label: "Condition",
      title: "Order value",
      detail: "amount > $100",
      icon: "◇",
    },
    {
      label: "Action",
      title: "Create fulfillment",
      detail: "POST /fulfillment",
      icon: "→",
    },
    {
      label: "Notify",
      title: "Post to Slack",
      detail: "#operations",
      icon: "◈",
    },
  ];
  const [activeStep, setActiveStep] = useState(-1);
  const [visible, setVisible] = useState(false);
  const shellRef = useRef<HTMLDivElement | null>(null);

  useEffect(() => {
    const el = shellRef.current;
    if (!el) return;
    const observer = new IntersectionObserver(
      ([entry]) => {
        if (entry.isIntersecting) setVisible(true);
      },
      { threshold: 0.22 },
    );
    observer.observe(el);
    return () => observer.disconnect();
  }, []);

  useEffect(() => {
    if (!visible) return;
    let cancelled = false;
    let timeout: ReturnType<typeof setTimeout> | undefined;
    const run = () => {
      if (cancelled) return;
      setActiveStep(-1);
      let step = 0;
      const advance = () => {
        if (cancelled) return;
        setActiveStep(step);
        step += 1;
        if (step <= steps.length) {
          timeout = setTimeout(advance, 720);
        } else {
          timeout = setTimeout(run, 2600);
        }
      };
      timeout = setTimeout(advance, 360);
    };
    run();
    return () => {
      cancelled = true;
      if (timeout) clearTimeout(timeout);
    };
  }, [visible, steps.length]);

  const builtCount = Math.max(0, Math.min(activeStep + 1, steps.length));
  const current = activeStep < 0 ? 0 : Math.min(activeStep, steps.length - 1);
  const isComplete = activeStep >= steps.length;

  return (
    <Reveal delay={90} className="workflow-builder-shell-wrap">
      <div
        ref={shellRef}
        className={`workflow-builder-shell workflow-builder-live-demo ${visible ? "is-visible" : ""} ${isComplete ? "is-complete" : ""}`}
      >
        <div className="workflow-builder-toolbar">
          <div className="workflow-builder-toolbar-left">
            <span
              className={`builder-live ${isComplete ? "builder-live-complete" : ""}`}
            >
              <i />{" "}
              {isComplete
                ? "Workflow ready"
                : activeStep < 0
                  ? "Building"
                  : "Building workflow"}
            </span>
            <span className="builder-divider" />
            <span className="builder-context">order_to_fulfillment</span>
          </div>
          <div className="workflow-builder-toolbar-actions">
            <span>
              {isComplete
                ? "Autosaved just now"
                : `${builtCount} / ${steps.length} steps`}
            </span>
            <button type="button">
              Test workflow <ArrowRight size={12} />
            </button>
          </div>
        </div>

        <div className="workflow-builder-body">
          <aside className="workflow-builder-palette">
            <span className="palette-label">ADD STEP</span>
            {steps.map((step, i) => (
              <div
                key={step.label}
                className={`palette-item palette-item-live ${i <= activeStep ? "is-built" : ""} ${i === current ? "is-current" : ""}`}
              >
                <b>{step.icon}</b>
                <div>
                  <strong>{step.label}</strong>
                  <small>
                    {i === 0
                      ? "Start a workflow"
                      : i === 1
                        ? "Branch on data"
                        : i === 2
                          ? "Call a service"
                          : "Send an alert"}
                  </small>
                </div>
                <span>{i <= activeStep ? "✓" : "+"}</span>
              </div>
            ))}
            <div className="palette-divider" />
            <span className="palette-label">CONNECTED</span>
            <div className="palette-connection">
              <i /> Stripe
            </div>
            <div className="palette-connection">
              <i /> Postgres
            </div>
            <div className="palette-connection">
              <i /> Slack
            </div>
          </aside>

          <div className="workflow-builder-canvas">
            <div className="builder-canvas-grid" aria-hidden="true" />
            <div className="builder-canvas-label">
              ORDER FULFILLMENT · {builtCount || 0} / 4 STEPS
            </div>
            <div className="builder-canvas-actions">
              <button type="button">−</button>
              <span>100%</span>
              <button type="button">+</button>
              <i />
              <button type="button">Fit</button>
            </div>

            <div className="workflow-node-stack workflow-node-stack-live">
              {steps.map((step, i) => (
                <div
                  className={`workflow-live-unit workflow-live-unit-${i}`}
                  key={step.label}
                >
                  <div
                    className={`workflow-node-card workflow-node-card-live ${i <= activeStep ? "is-built" : ""} ${i === current ? "is-current" : ""}`}
                  >
                    <span className="workflow-node-icon">{step.icon}</span>
                    <div>
                      <small>{step.label.toUpperCase()}</small>
                      <strong>{step.title}</strong>
                      <em>{step.detail}</em>
                    </div>
                    <span className="workflow-node-menu">•••</span>
                    {i === current && !isComplete && (
                      <span
                        className="workflow-node-pulse"
                        aria-hidden="true"
                      />
                    )}
                  </div>
                  {i < steps.length - 1 && (
                    <span
                      className={`workflow-node-link workflow-node-link-live ${i < activeStep ? "is-built" : ""}`}
                    >
                      <i />
                    </span>
                  )}
                </div>
              ))}
            </div>

            <div
              className={`workflow-builder-complete ${isComplete ? "show" : ""}`}
            >
              <span>
                <i /> Workflow valid
              </span>
              <b>4 steps</b>
              <b>3 connections</b>
            </div>
          </div>

          <aside className="workflow-builder-inspector">
            <span className="palette-label">STEP SETTINGS</span>
            <div className="inspector-title inspector-title-live">
              <span>{steps[current].icon}</span>
              <div>
                <small>{steps[current].label.toUpperCase()}</small>
                <strong>{steps[current].title}</strong>
              </div>
            </div>
            <label>
              {current === 0
                ? "Event"
                : current === 1
                  ? "Rule"
                  : current === 2
                    ? "Endpoint"
                    : "Channel"}
            </label>
            <div className="inspector-field">
              {steps[current].detail} <span>⌄</span>
            </div>
            <label>
              {current === 0
                ? "Endpoint"
                : current === 1
                  ? "Branch"
                  : current === 2
                    ? "Method"
                    : "Severity"}
            </label>
            <div className="inspector-field">
              {current === 0
                ? "/hooks/orders"
                : current === 1
                  ? "YES → continue"
                  : current === 2
                    ? "POST"
                    : "Operational"}{" "}
              <span>↗</span>
            </div>
            <div className="inspector-toggle">
              <span>Retry on failure</span>
              <i />
            </div>
            <div className="inspector-divider" />
            <div className="inspector-note">
              <span>Reliability</span>
              <p>
                Retries, execution traces, and failure context are enabled
                automatically.
              </p>
            </div>
          </aside>
        </div>
      </div>
    </Reveal>
  );
}
