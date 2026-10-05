"use client";

import { Children, type ReactNode } from "react";

/**
 * Sticky "stacking cards" scroll effect.
 *
 * Each child pins just under the header while the next one slides up and
 * covers it. Wrap any consecutive sections in <StackGroup>; no edits to the
 * sections themselves are needed.
 *
 *   <StackGroup>
 *     <MonitorSection />
 *     <DetectSection />
 *     <DiagnoseSection />
 *   </StackGroup>
 */
export function StackGroup({ children }: { children: ReactNode }) {
  return (
    <div className="stack-group">
      {Children.map(children, (child) => (
        <div className="stack-card">{child}</div>
      ))}
      <StackedStyles />
    </div>
  );
}

function StackedStyles() {
  return (
    <style jsx global>
      {`
        :root {
          /* Height of your sticky header. Adjust if cards pin too high/low. */
          --stack-top: 72px;
        }

        /* Anchor links (#platform etc.) should land below the header */
        .stack-card > section[id] {
          scroll-margin-top: var(--stack-top);
        }

        /* Desktop only, and only for people who haven't asked for reduced motion.
           On mobile / reduced motion the sections just scroll normally. */
        @media (min-width: 901px) and (prefers-reduced-motion: no-preference) {
          .stack-group {
            position: relative;
          }

          .stack-card {
            position: sticky;
            top: var(--stack-top);
            min-height: calc(100svh - var(--stack-top));
            display: flex;
            flex-direction: column;
            justify-content: center;

            /* Must be opaque, or the card underneath shows through */
            background: #050505;
            border-top: 1px solid rgba(255, 255, 255, 0.09);
            border-radius: 24px 24px 0 0;
            box-shadow: 0 -28px 70px rgba(0, 0, 0, 0.6);
          }

          /* Trim the tall editorial padding so each card fits one screen */
          .stack-card > section {
            padding-top: clamp(40px, 7vh, 88px) !important;
            padding-bottom: clamp(40px, 7vh, 88px) !important;
          }
        }
      `}
    </style>
  );
}