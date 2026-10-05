import type { Config } from "tailwindcss";

const config: Config = {
  darkMode: ["class"],
  content: ["./app/**/*.{ts,tsx}", "./components/**/*.{ts,tsx}"],
  theme: {
    container: {
      center: true,
      padding: "2rem",
      screens: { "2xl": "1400px" },
    },
    extend: {
      colors: {
        /* Brand */
        brand: {
          400: "#a78bfa",
          500: "#818cf8",
          600: "#6366f1",
          700: "#4f46e5",
        },

        /* Surface tokens (Single source of truth) */
        surface: {
          page: "#000000",
          shell: "#050505",
          card: "#0a0a0c",
          inset: "#111114",
          hover: "rgba(255, 255, 255, 0.04)",
          active: "rgba(255, 255, 255, 0.07)",
        },

        /* Legacy surface aliases */
        "surface-0": "#000000",
        "surface-1": "#050505",
        "surface-2": "#0a0a0c",
        "surface-3": "#111114",
        "bone-white": "#fafafa",
        "off-white": "#e4e4e7",
        "mute-gray": "#71717a",
        "wire-gray": "#27272a",
        "ghost-gray": "#18181b",
        "console-charcoal": "#0a0a0c",
        "true-black": "#000000",
        "nav-ink": "#050505",
        "recess-black": "#0a0a0c",
        smoke: "#111114",
        ash: "#71717a",

        /* Text contrast tokens */
        txt: {
          primary: "rgba(255, 255, 255, 0.90)",
          body: "rgba(255, 255, 255, 0.65)",
          secondary: "rgba(255, 255, 255, 0.45)",
          muted: "rgba(255, 255, 255, 0.45)",
          faint: "rgba(255, 255, 255, 0.35)",
        },

        /* Status colors */
        status: {
          success: {
            DEFAULT: "#34d399",
            text: "#34d399",
            bg: "rgba(16, 185, 129, 0.08)",
            border: "rgba(16, 185, 129, 0.20)",
            dot: "#34d399",
          },
          warning: {
            DEFAULT: "#fbbf24",
            text: "#fbbf24",
            bg: "rgba(245, 158, 11, 0.08)",
            border: "rgba(245, 158, 11, 0.20)",
            dot: "#fbbf24",
          },
          danger: {
            DEFAULT: "#f87171",
            text: "#f87171",
            bg: "rgba(239, 68, 68, 0.08)",
            border: "rgba(239, 68, 68, 0.20)",
            dot: "#f87171",
          },
          info: {
            DEFAULT: "#60a5fa",
            text: "#60a5fa",
            bg: "rgba(59, 130, 246, 0.08)",
            border: "rgba(59, 130, 246, 0.20)",
            dot: "#60a5fa",
          },
          active: {
            DEFAULT: "#34d399",
            text: "#34d399",
            bg: "rgba(16, 185, 129, 0.08)",
            border: "rgba(16, 185, 129, 0.20)",
            dot: "#34d399",
          },
          neutral: {
            DEFAULT: "#a1a1aa",
            text: "#a1a1aa",
            bg: "rgba(255, 255, 255, 0.04)",
            border: "rgba(255, 255, 255, 0.08)",
            dot: "#a1a1aa",
          },
        },

        /* shadcn/ui HSL mappings */
        border: {
          DEFAULT: "hsl(var(--border))",
          subtle: "rgba(255, 255, 255, 0.065)",
          strong: "rgba(255, 255, 255, 0.12)",
          hover: "rgba(255, 255, 255, 0.16)",
        },
        input: "hsl(var(--input))",
        ring: "hsl(var(--ring))",
        background: "hsl(var(--background))",
        foreground: "hsl(var(--foreground))",
        primary: {
          DEFAULT: "hsl(var(--primary))",
          foreground: "hsl(var(--primary-foreground))",
        },
        secondary: {
          DEFAULT: "hsl(var(--secondary))",
          foreground: "hsl(var(--secondary-foreground))",
        },
        destructive: {
          DEFAULT: "hsl(var(--destructive))",
          foreground: "hsl(var(--destructive-foreground))",
        },
        muted: {
          DEFAULT: "hsl(var(--muted))",
          foreground: "hsl(var(--muted-foreground))",
        },
        accent: {
          DEFAULT: "hsl(var(--accent))",
          foreground: "hsl(var(--accent-foreground))",
        },
        popover: {
          DEFAULT: "hsl(var(--popover))",
          foreground: "hsl(var(--popover-foreground))",
        },
        card: {
          DEFAULT: "hsl(var(--card))",
          foreground: "hsl(var(--card-foreground))",
        },
        success: {
          DEFAULT: "#34d399",
          foreground: "#ffffff",
        },
        warning: {
          DEFAULT: "#fbbf24",
          foreground: "#ffffff",
        },
      },
      backgroundImage: {
        "brand-gradient": "var(--gradient-brand)",
        "brand-soft": "var(--gradient-brand-soft)",
        "aurora": "radial-gradient(40% 50% at 20% 20%, rgba(129,140,248,0.18), transparent 70%), radial-gradient(40% 50% at 80% 30%, rgba(167,139,250,0.16), transparent 70%), radial-gradient(50% 50% at 60% 80%, rgba(99,102,241,0.14), transparent 70%)",
      },
      borderRadius: {
        lg: "0.5rem", // 8px (controls)
        md: "0.375rem", // 6px (chips)
        sm: "0.25rem", // 4px
        xl: "0.75rem", // 12px (cards)
        tags: "0.375rem",
        cards: "0.75rem",
        controls: "0.5rem",
        chips: "0.375rem",
        icons: "0.5rem",
        inputs: "0.5rem",
        buttons: "0.5rem",
        "large-buttons": "0.5rem",
      },
      fontFamily: {
        sans: ["var(--font-geistsans)", "system-ui", "sans-serif"],
        mono: ["var(--font-geistmono)", "ui-monospace", "monospace"],
        geistsans: ["var(--font-geistsans)", "system-ui", "sans-serif"],
        geistmono: ["var(--font-geistmono)", "ui-monospace", "monospace"],
      },
      fontSize: {
        "page-title": ["24px", { lineHeight: "32px", letterSpacing: "-0.03em", fontWeight: "600" }],
        "section-title": ["14px", { lineHeight: "20px", letterSpacing: "-0.01em", fontWeight: "500" }],
        body: ["13px", { lineHeight: "19px", letterSpacing: "-0.01em" }],
        caption: ["11px", { lineHeight: "15px", letterSpacing: "0em" }],
        eyebrow: ["10px", { lineHeight: "14px", letterSpacing: "0.18em", fontWeight: "600" }],
        "body-sm": ["13px", { lineHeight: "19px", letterSpacing: "-0.01em" }],
        subheading: ["16px", { lineHeight: "24px", letterSpacing: "-0.02em" }],
        heading: ["20px", { lineHeight: "28px", letterSpacing: "-0.03em" }],
        "heading-lg": ["24px", { lineHeight: "32px", letterSpacing: "-0.03em" }],
        display: ["30px", { lineHeight: "38px", letterSpacing: "-0.04em" }],
        "display-xl": ["36px", { lineHeight: "44px", letterSpacing: "-0.04em" }],
      },
      spacing: {
        /* Only override sizes that Tailwind doesn't provide by default */
        "80": "var(--spacing-80)",
        "112": "var(--spacing-112)",
        "160": "var(--spacing-160)",
      },
      boxShadow: {
        card: "var(--shadow-card)",
        "card-hover": "var(--shadow-card-hover)",
        glow: "var(--shadow-glow)",
        float: "var(--shadow-float)",
        subtle: "var(--shadow-subtle)",
        elevated: "var(--shadow-elevated)",
      },
      keyframes: {
        "accordion-down": {
          from: { height: "0" },
          to: { height: "var(--radix-accordion-content-height)" },
        },
        "accordion-up": {
          from: { height: "var(--radix-accordion-content-height)" },
          to: { height: "0" },
        },
        "flow-dash": {
          to: { strokeDashoffset: "-16" },
        },
        float: {
          "0%, 100%": { transform: "translateY(0)" },
          "50%": { transform: "translateY(-12px)" },
        },
        "float-slow": {
          "0%, 100%": { transform: "translateY(0) rotate(0deg)" },
          "50%": { transform: "translateY(-20px) rotate(2deg)" },
        },
        shimmer: {
          "100%": { transform: "translateX(100%)" },
        },
        "gradient-shift": {
          "0%, 100%": { backgroundPosition: "0% 50%" },
          "50%": { backgroundPosition: "100% 50%" },
        },
        "pulse-glow": {
          "0%, 100%": { opacity: "0.6", transform: "scale(1)" },
          "50%": { opacity: "1", transform: "scale(1.05)" },
        },
        rise: {
          from: { opacity: "0", transform: "translateY(16px)" },
          to: { opacity: "1", transform: "translateY(0)" },
        },
      },
      animation: {
        "accordion-down": "accordion-down 0.2s ease-out",
        "accordion-up": "accordion-up 0.2s ease-out",
        "flow-dash": "flow-dash 1s linear infinite",
        float: "float 6s ease-in-out infinite",
        "float-slow": "float-slow 9s ease-in-out infinite",
        shimmer: "shimmer 1.5s infinite",
        "gradient-shift": "gradient-shift 8s ease infinite",
        "pulse-glow": "pulse-glow 3s ease-in-out infinite",
        rise: "rise 0.5s ease-out both",
      },
    },
  },
  plugins: [require("tailwindcss-animate")],
};

export default config;
