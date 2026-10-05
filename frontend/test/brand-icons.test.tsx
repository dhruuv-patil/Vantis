import { describe, it, expect } from "vitest";
import React from "react";
import { render } from "@testing-library/react";
import {
  BrandIcon,
  resolveTheSvgSlug,
  isMonoDarkBrand,
  theSvgUrl,
  getBrandTitle,
  getBrandColor,
} from "@/components/app/builder/node-visuals";

describe("Brand Icon Resolution and Themes", () => {
  describe("resolveTheSvgSlug", () => {
    it("resolves common providers by type or keyword", () => {
      expect(resolveTheSvgSlug("github")).toBe("github");
      expect(resolveTheSvgSlug("github_actions")).toBe("github-actions");
      expect(resolveTheSvgSlug("slack")).toBe("slack");
      expect(resolveTheSvgSlug("discord")).toBe("discord");
      expect(resolveTheSvgSlug("stripe")).toBe("stripe");
      expect(resolveTheSvgSlug("notion")).toBe("notion");
      expect(resolveTheSvgSlug("openai")).toBe("openai");
      expect(resolveTheSvgSlug("anthropic")).toBe("anthropic");
      expect(resolveTheSvgSlug("postgresql")).toBe("postgresql");
      expect(resolveTheSvgSlug("postgres")).toBe("postgresql");
      expect(resolveTheSvgSlug("mysql")).toBe("mysql");
      expect(resolveTheSvgSlug("mongodb")).toBe("mongodb");
      expect(resolveTheSvgSlug("redis")).toBe("redis");
      expect(resolveTheSvgSlug("google_cloud")).toBe("google-cloud");
      expect(resolveTheSvgSlug("azure")).toBe("azure");
      expect(resolveTheSvgSlug("make")).toBe("make");
      expect(resolveTheSvgSlug("temporal")).toBe("temporal");
    });

    it("returns null for unknown generic nodes", () => {
      expect(resolveTheSvgSlug("custom_filter")).toBeNull();
      expect(resolveTheSvgSlug("unknown_random_tool")).toBeNull();
    });
  });

  describe("theSvgUrl and brand metadata", () => {
    it("constructs CDN URLs correctly", () => {
      expect(theSvgUrl("github")).toBe("https://thesvg.org/icons/github/default.svg");
      expect(theSvgUrl("slack")).toBe("https://thesvg.org/icons/slack/default.svg");
    });

    it("retrieves human-friendly titles", () => {
      expect(getBrandTitle("github")).toBe("GitHub");
      expect(getBrandTitle("postgresql")).toBe("PostgreSQL");
      expect(getBrandTitle("google-cloud")).toBe("Google Cloud");
    });

    it("retrieves brand accent colors", () => {
      expect(getBrandColor("slack")).toBe("#E01E5A");
      expect(getBrandColor("stripe")).toBe("#635BFF");
    });
  });

  describe("Dark & Light Theme styling for mono-dark icons", () => {
    it("identifies mono-dark brands", () => {
      expect(isMonoDarkBrand("github")).toBe(true);
      expect(isMonoDarkBrand("notion")).toBe(true);
      expect(isMonoDarkBrand("openai")).toBe(true);
      expect(isMonoDarkBrand("vercel")).toBe(true);
      // Multi-color brands are not mono-dark
      expect(isMonoDarkBrand("slack")).toBe(false);
      expect(isMonoDarkBrand("google-cloud")).toBe(false);
    });

    it("applies theme='dark' invert styling to mono-dark logos", () => {
      const { container } = render(
        <BrandIcon slug="github" theme="dark" alt="GitHub Logo" />
      );
      const img = container.querySelector("img");
      expect(img).not.toBeNull();
      expect(img?.className).toContain("brightness-0 invert");
      expect(img?.getAttribute("src")).toBe("https://thesvg.org/icons/github/default.svg");
    });

    it("does not invert mono-dark logos when theme='light'", () => {
      const { container } = render(
        <BrandIcon slug="github" theme="light" alt="GitHub Logo" />
      );
      const img = container.querySelector("img");
      expect(img).not.toBeNull();
      expect(img?.className).not.toContain("brightness-0 invert");
      expect(img?.className).not.toContain("dark:brightness-0");
    });

    it("uses Tailwind dynamic classes when theme='auto'", () => {
      const { container } = render(
        <BrandIcon slug="github" theme="auto" alt="GitHub Logo" />
      );
      const img = container.querySelector("img");
      expect(img).not.toBeNull();
      expect(img?.className).toContain("dark:brightness-0 dark:invert");
    });

    it("does not invert colored brands in dark or light mode", () => {
      const { container } = render(
        <BrandIcon slug="slack" theme="dark" alt="Slack Logo" />
      );
      const img = container.querySelector("img");
      expect(img).not.toBeNull();
      expect(img?.className).not.toContain("invert");
    });
  });
});
