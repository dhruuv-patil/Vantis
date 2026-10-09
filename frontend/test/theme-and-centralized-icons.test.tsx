import { describe, it, expect, vi, afterEach } from "vitest";
import React from "react";
import { render, cleanup, fireEvent } from "@testing-library/react";
import { ThemeToggle } from "@/components/theme-toggle";
import {
  BrandIcon,
  ProviderIcon,
  NodeIcon,
  resolveTheSvgSlug,
  isMonoDarkBrand,
  CAPABILITY_ICONS,
} from "@/components/brand/brand-icon";

// Mock next-themes
const mockSetTheme = vi.fn();
let mockTheme = "dark";
let mockResolvedTheme = "dark";

vi.mock("next-themes", () => ({
  useTheme: () => ({
    theme: mockTheme,
    resolvedTheme: mockResolvedTheme,
    setTheme: mockSetTheme,
  }),
}));

afterEach(() => {
  cleanup();
  mockSetTheme.mockClear();
});

describe("Dark & Light Theme Toggle", () => {
  it("renders the theme toggle button with accessible attributes", () => {
    mockResolvedTheme = "dark";
    const { container } = render(<ThemeToggle />);
    const button = container.querySelector("button");
    expect(button).not.toBeNull();
    expect(button?.getAttribute("title")).toContain("Switch to light theme");
    expect(button?.getAttribute("aria-label")).toContain("Switch to light theme");
  });

  it("calls setTheme('light') when clicked in dark mode", () => {
    mockResolvedTheme = "dark";
    const { container } = render(<ThemeToggle />);
    const button = container.querySelector("button");
    expect(button).not.toBeNull();
    fireEvent.click(button!);
    expect(mockSetTheme).toHaveBeenCalledWith("light");
  });

  it("calls setTheme('dark') when clicked in light mode", () => {
    mockResolvedTheme = "light";
    const { container } = render(<ThemeToggle />);
    const button = container.querySelector("button");
    expect(button).not.toBeNull();
    fireEvent.click(button!);
    expect(mockSetTheme).toHaveBeenCalledWith("dark");
  });

  it("renders segmented control variant with light, dark, and system options", () => {
    mockTheme = "dark";
    const { container } = render(<ThemeToggle variant="segmented" />);
    const buttons = container.querySelectorAll("button");
    expect(buttons.length).toBe(3);

    const [lightBtn, darkBtn, systemBtn] = Array.from(buttons);
    expect(lightBtn.textContent).toContain("Light");
    expect(darkBtn.textContent).toContain("Dark");
    expect(systemBtn.textContent).toContain("System");

    fireEvent.click(lightBtn);
    expect(mockSetTheme).toHaveBeenCalledWith("light");

    fireEvent.click(systemBtn);
    expect(mockSetTheme).toHaveBeenCalledWith("system");
  });
});

describe("Centralized Icon System (Templates, Integrations, Flow-Nodes)", () => {
  describe("Brand and capability resolution", () => {
    it("resolves providers used in templates & integrations", () => {
      expect(resolveTheSvgSlug("github")).toBe("github");
      expect(resolveTheSvgSlug("slack")).toBe("slack");
      expect(resolveTheSvgSlug("stripe")).toBe("stripe");
      expect(resolveTheSvgSlug("google_sheets")).toBe("google-sheets");
      expect(resolveTheSvgSlug("google-calendar")).toBe("google-calendar");
      expect(resolveTheSvgSlug("postgresql")).toBe("postgresql");
      expect(resolveTheSvgSlug("redis")).toBe("redis");
      expect(resolveTheSvgSlug("openai")).toBe("openai");
      expect(resolveTheSvgSlug("anthropic")).toBe("anthropic");
      expect(resolveTheSvgSlug("make")).toBe("make");
      expect(resolveTheSvgSlug("n8n")).toBe("n8n");
      expect(resolveTheSvgSlug("zapier")).toBe("zapier");
    });

    it("identifies mono-dark brands that adapt across dark & light themes", () => {
      expect(isMonoDarkBrand("github")).toBe(true);
      expect(isMonoDarkBrand("vercel")).toBe(true);
      expect(isMonoDarkBrand("notion")).toBe(true);
      expect(isMonoDarkBrand("openai")).toBe(true);
      expect(isMonoDarkBrand("anthropic")).toBe(true);
      expect(isMonoDarkBrand("slack")).toBe(false);
      expect(isMonoDarkBrand("google-sheets")).toBe(false);
    });

    it("has built-in capability definitions for native flow-nodes & templates", () => {
      expect(CAPABILITY_ICONS.ai).toBeDefined();
      expect(CAPABILITY_ICONS.webhook).toBeDefined();
      expect(CAPABILITY_ICONS.http).toBeDefined();
      expect(CAPABILITY_ICONS.approval).toBeDefined();
      expect(CAPABILITY_ICONS.email).toBeDefined();
    });
  });

  describe("ProviderIcon (used in Integrations & Dialogs)", () => {
    it("renders ProviderIcon with theme='auto' dynamic invert class for mono-dark", () => {
      const { container } = render(
        <ProviderIcon provider="github" name="GitHub" className="size-6" />
      );
      const img = container.querySelector("img");
      expect(img).not.toBeNull();
      expect(img?.className).toContain("dark:brightness-0 dark:invert");
    });

    it("renders ProviderIcon without invert for colored brands (e.g. Slack)", () => {
      const { container } = render(
        <ProviderIcon provider="slack" name="Slack" className="size-6" />
      );
      const img = container.querySelector("img");
      expect(img).not.toBeNull();
      expect(img?.className).not.toContain("invert");
    });

    it("renders fallback initials when requested and no icon matches", () => {
      const { container } = render(
        <ProviderIcon
          provider="custom_service_xyz"
          name="Acme Service"
          fallbackInitials
        />
      );
      expect(container.textContent).toContain("AS");
    });
  });

  describe("NodeIcon (used in Flow-Node canvas & NodePalette)", () => {
    it("renders NodeIcon in canvas variant with dark:drop-shadow", () => {
      const { container } = render(
        <NodeIcon type="github" label="GitHub Trigger" variant="node" />
      );
      const img = container.querySelector("img");
      expect(img).not.toBeNull();
      expect(img?.className).toContain("drop-shadow");
      expect(img?.className).toContain("dark:brightness-0 dark:invert");
    });

    it("renders NodeIcon in palette variant inside fixed slot", () => {
      const { container } = render(
        <NodeIcon type="slack" label="Post to Slack" variant="palette" />
      );
      const span = container.querySelector("span");
      expect(span).not.toBeNull();
      expect(span?.className).toContain("size-8");
      const img = container.querySelector("img");
      expect(img).not.toBeNull();
      expect(img?.className).toContain("size-[24px]");
    });
  });

  describe("BrandIcon in Templates (used for template preview trays)", () => {
    it("renders brand icons with theme='auto'", () => {
      const { container } = render(
        <BrandIcon
          slug="google-sheets"
          name="Google Sheets"
          className="size-[22px]"
          theme="auto"
        />
      );
      const img = container.querySelector("img");
      expect(img).not.toBeNull();
      expect(img?.getAttribute("src")).toContain("google-sheets");
    });

    it("renders capability icons in template cards when not a 3rd party brand", () => {
      const { container } = render(
        <BrandIcon
          type="webhook"
          label="Webhook"
          className="size-5"
          theme="auto"
        />
      );
      const svg = container.querySelector("svg");
      expect(svg).not.toBeNull();
    });
  });
});
