"use client";

import { useState } from "react";
import { ChevronDown } from "lucide-react";
import { faqs, faqAnswers } from "../data";
import { Reveal } from "../primitives";

export function FaqSection() {
  const [faq, setFaq] = useState<number | null>(0);

  return (
    <section id="faq" className="faq-section">
      <Reveal className="reveal-fade">
        <div className="eyebrow center">Questions, answered</div>
        <h2>Frequently asked.</h2>
      </Reveal>
      {faqs.map((x, i) => (
        <Reveal key={x} delay={i * 50} className="faq">
          <button onClick={() => setFaq(faq === i ? null : i)}>
            {x}
            <ChevronDown className={faq === i ? "rotate" : ""} size={18} />
          </button>
          <div
            className="faq-collapse"
            style={{ gridTemplateRows: faq === i ? "1fr" : "0fr" }}
          >
            <div className="faq-collapse-inner">
              <p>{faqAnswers[i]}</p>
            </div>
          </div>
        </Reveal>
      ))}
    </section>
  );
}
