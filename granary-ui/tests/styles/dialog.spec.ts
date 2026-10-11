import { readFileSync } from 'node:fs';
import { fileURLToPath, URL as NodeURL } from 'node:url';
import { expect, it } from 'vitest';

const css = readFileSync(fileURLToPath(new NodeURL('../../src/styles/main.css', import.meta.url)), 'utf8');

/** The body of every rule whose selector list is exactly `selector`. */
function rules(selector: string): string[] {
  const escaped = selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  return [...css.matchAll(new RegExp(`(?:^|\\n|\\})\\s*${escaped}\\s*\\{([^}]*)\\}`, 'g'))].map((m) => m[1]!);
}

it('lays out only open dialogs, so a closed one leaves no empty box on the page', () => {
  // author styles beat the browser's dialog:not([open]) { display: none }
  expect(rules('.dialog').length).toBeGreaterThan(0);
  for (const body of rules('.dialog')) {
    expect(body).not.toMatch(/(^|[\s;])display\s*:/);
  }
  expect(rules('.dialog[open]').join()).toMatch(/display\s*:\s*flex/);
});
