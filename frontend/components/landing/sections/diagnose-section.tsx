"use client";

import { Reveal, Window } from "../primitives";

export function DiagnoseSection() {
  return (
    <section className="section reverse">
      <Reveal delay={0} className="reveal-left hero-window-wrap">
        <Window image="https://images.unsplash.com/photo-1473445361085-b9a07f55608b?auto=format&fit=crop&w=1800&q=85">
          <div className="code-card">
            <div className="flex items-center justify-between">
              <h3>Failure details</h3>
              <span className="status yellow-text">
                <b /> Needs attention
              </span>
            </div>
            <pre>{`error: TimeoutError\nstep: sync_customer_to_warehouse\nduration: 30.04s · retry: 2/3\n\nSuggested: inspect warehouse connection pool`}</pre>
          </div>
        </Window>
      </Reveal>
      <Reveal delay={100} className="copy reveal-right">
        <div className="eyebrow">Diagnose</div>
        <h2>Make the invisible failure visible.</h2>
        <p>
          Inspect the execution, step-level logs, and failure context without
          jumping between five different tools.
        </p>
        <div className="pills">
          <span>Structured traces</span>
          <span>Execution context</span>
          <span>Impact analysis</span>
        </div>
      </Reveal>
    </section>
  );
}
