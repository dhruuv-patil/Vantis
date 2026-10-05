"use client";

import { Reveal } from "../primitives";
import { WorkflowBuilderDemo } from "../workflow-builder-demo";

export function WorkflowBuilderSection() {
  return (
    <section id="workflow-builder" className="flowops-workflow-builder-section">
      <Reveal className="reveal-fade workflow-builder-intro">
        <div className="eyebrow center">Workflow builder</div>
        <h2>
          Build the workflow.
          <br />
          <em>Then make it reliable.</em>
        </h2>
        <p>
          Design triggers, conditions, actions, and notifications visually. Test
          the path before production and keep every execution observable from
          day one.
        </p>
      </Reveal>

      <WorkflowBuilderDemo />
    </section>
  );
}