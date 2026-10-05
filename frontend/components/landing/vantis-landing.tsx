"use client";

import { LandingHeader } from "./landing-header";
import { HeroSection } from "./hero-section";
import { MonitorSection } from "./sections/monitor-section";
import { DetectSection } from "./sections/detect-section";
import { DiagnoseSection } from "./sections/diagnose-section";
import { MessyMiddleSection } from "./sections/messy-middle-section";
import { ReliabilityLayerSection } from "./sections/reliability-layer-section";
import { IncidentJourneySection } from "./sections/incident-journey-section";
import { OutcomesSection } from "./sections/outcomes-section";
import { IntegrationsSection } from "./sections/integrations-section";
import { WorkflowBuilderSection } from "./sections/workflow-builder-section";
import { CopilotSection } from "./sections/copilot-section";
import { SecuritySection } from "./sections/security-section";
import { FaqSection } from "./sections/faq-section";
import { GetStartedSection } from "./sections/get-started-section";
import { LandingStyles } from "./landing-styles";
import { LandingFooter } from "./landing-footer";
import { StackGroup } from "./stacked-sections";

export default function VantisLanding() {
  return (
    <main>
      <LandingHeader />
      <HeroSection />
      <StackGroup>
        <MonitorSection />
        <DetectSection />
        <DiagnoseSection />
      </StackGroup>
      <MessyMiddleSection />
      <ReliabilityLayerSection />
      <IncidentJourneySection />
      <OutcomesSection />
      <IntegrationsSection />
      <WorkflowBuilderSection />
      <CopilotSection />
      <SecuritySection />
      <FaqSection />
      <GetStartedSection />
      <LandingStyles />
      <LandingFooter />
    </main>
  );
}
