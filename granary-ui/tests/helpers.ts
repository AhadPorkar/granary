import { mount, type ComponentMountingOptions } from '@vue/test-utils';
import { createPinia, setActivePinia, type Pinia } from 'pinia';
import type { Component } from 'vue';
import { i18n } from '@/i18n';

export function freshPinia(): Pinia {
  const pinia = createPinia();
  setActivePinia(pinia);
  return pinia;
}

/** Mounts a component with Pinia and the real i18n instance; router links are stubbed. */
export function mountWithPlugins<C extends Component>(component: C, options: ComponentMountingOptions<C> = {}) {
  const pinia = freshPinia();
  return mount(component, {
    ...options,
    global: {
      plugins: [pinia, i18n],
      stubs: { RouterLink: { template: '<a><slot /></a>' }, ...(options.global?.stubs as Record<string, unknown> | undefined) },
      ...(options.global ?? {}),
    },
  });
}

export function jsonResponse(body: unknown, status = 200, headers: Record<string, string> = {}): Response {
  return new Response(body === undefined ? null : JSON.stringify(body), {
    status,
    headers: { 'content-type': 'application/json', ...headers },
  });
}

/** Recursively flattens {a: {b: 'x'}} into {'a.b': 'x'}. */
export function flatten(value: unknown, prefix = ''): Record<string, string> {
  const out: Record<string, string> = {};
  if (value && typeof value === 'object' && !Array.isArray(value)) {
    for (const [key, child] of Object.entries(value as Record<string, unknown>)) {
      Object.assign(out, flatten(child, prefix ? `${prefix}.${key}` : key));
    }
  } else {
    out[prefix] = String(value);
  }
  return out;
}
