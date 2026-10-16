import { faqs, faqAnswers } from "@/components/landing/data";

export function JsonLd() {
  const schema = {
    "@context": "https://schema.org",
    "@graph": [
      {
        "@type": "WebSite",
        "@id": "https://vantis.run/#website",
        "url": "https://vantis.run",
        "name": "Vantis",
        "description":
          "Reliability infrastructure for automation. Monitor workflows, detect anomalies, and investigate failures.",
        "inLanguage": "en-US",
      },
      {
        "@type": "Organization",
        "@id": "https://vantis.run/#organization",
        "name": "Vantis",
        "url": "https://vantis.run",
        "logo": {
          "@type": "ImageObject",
          "url": "https://vantis.run/logo-mark.svg",
        },
      },
      {
        "@type": "SoftwareApplication",
        "@id": "https://vantis.run/#software",
        "name": "Vantis",
        "applicationCategory": "DeveloperApplication",
        "operatingSystem": "All",
        "url": "https://vantis.run",
        "description":
          "Reliability infrastructure for automation. Build, monitor workflows, detect anomalies, and investigate failures across n8n, Zapier, Temporal, and custom APIs.",
        "offers": {
          "@type": "Offer",
          "price": "0",
          "priceCurrency": "USD",
          "description": "Free during private beta",
        },
      },
      {
        "@type": "FAQPage",
        "@id": "https://vantis.run/#faq",
        "mainEntity": faqs.map((q, i) => ({
          "@type": "Question",
          "name": q,
          "acceptedAnswer": {
            "@type": "Answer",
            "text": faqAnswers[i] ?? "",
          },
        })),
      },
    ],
  };

  return (
    <script
      type="application/ld+json"
      dangerouslySetInnerHTML={{ __html: JSON.stringify(schema) }}
    />
  );
}
