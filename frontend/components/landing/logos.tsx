"use client";

export function SlackLogo() {
  return (
    <svg
      viewBox="0 0 24 24"
      className="integration-svg-logo slack-logo"
      aria-hidden="true"
    >
      <path
        fill="#36C5F0"
        d="M7.6 2.5a2.35 2.35 0 1 0 0 4.7H10V4.85A2.35 2.35 0 0 0 7.6 2.5Z"
      />
      <path
        fill="#2EB67D"
        d="M21.5 7.6a2.35 2.35 0 1 0-4.7 0V10h2.35a2.35 2.35 0 0 0 2.35-2.4Z"
      />
      <path
        fill="#ECB22E"
        d="M16.4 21.5a2.35 2.35 0 1 0 0-4.7H14.1v2.35a2.35 2.35 0 0 0 2.3 2.35Z"
      />
      <path
        fill="#E01E5A"
        d="M2.5 16.4a2.35 2.35 0 1 0 4.7 0v-2.3H4.85A2.35 2.35 0 0 0 2.5 16.4Z"
      />
      <path fill="#36C5F0" d="M10 7.2H7.6a2.35 2.35 0 1 0 0 4.7H10V7.2Z" />
      <path fill="#2EB67D" d="M16.8 10V7.6a2.35 2.35 0 1 0-4.7 0V10h4.7Z" />
      <path fill="#ECB22E" d="M14 16.8h2.4a2.35 2.35 0 1 0 0-4.7H14v4.7Z" />
      <path fill="#E01E5A" d="M7.2 14v2.4a2.35 2.35 0 1 0 4.7 0V14H7.2Z" />
    </svg>
  );
}

export function TeamsLogo() {
  return (
    <svg
      viewBox="0 0 24 24"
      className="integration-svg-logo teams-logo"
      aria-hidden="true"
    >
      <circle cx="18.2" cy="5.8" r="2.3" fill="#7B83EB" />
      <path d="M16.4 9.1h4.2v7.1a2.1 2.1 0 0 1-2.1 2.1h-2.1z" fill="#5B5FC7" />
      <rect x="2.5" y="6" width="13" height="13" rx="2.2" fill="#6264A7" />
      <path d="M6.1 9.1h5.8v2.1H10v5.7H7.9v-5.7H6.1z" fill="#FFF" />
    </svg>
  );
}
