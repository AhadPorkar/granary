import { describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import AccountView from '@/views/AccountView.vue';
import type { TokenView } from '@/api/types';
import { i18n } from '@/i18n';
import { useAuthStore } from '@/stores/auth';
import { freshPinia } from '../helpers';

vi.mock('@/api', () => ({
  api: {
    tokens: {
      list: () =>
        Promise.resolve([
          { id: 't1', name: 'github-actions-release-pipeline', prefix: 'gry_VGq6RHwP', createdAt: '2026-10-01T10:00:00Z', expiresAt: null, lastUsedAt: null },
        ] as TokenView[]),
    },
  },
}));

async function mountView() {
  const pinia = freshPinia();
  useAuthStore().username = 'admin';
  const wrapper = mount(AccountView, { global: { plugins: [pinia, i18n], stubs: { RouterLink: { template: '<a><slot /></a>' } } } });
  await flushPromises();
  return wrapper;
}

describe('AccountView', () => {
  it('lists tokens in a table that stacks on phones and names each revoke button', async () => {
    const wrapper = await mountView();
    expect(wrapper.find('table').classes()).toContain('table--stack');
    const revoke = wrapper.find('tbody button.btn--danger-ghost');
    expect(revoke.attributes('aria-label')).toBe(i18n.global.t('account.tokens.revokeName', { name: 'github-actions-release-pipeline' }));
    expect(revoke.attributes('aria-label')).toContain('github-actions-release-pipeline');
  });

  it('writes the minimum password length with the digits of the UI language', async () => {
    i18n.global.locale.value = 'fa';
    const wrapper = await mountView();
    const hint = wrapper.find('[data-field="pw-new"] .field__hint').text();
    expect(hint).toContain('\u06f8'); // 8 in Persian digits
    expect(hint).not.toMatch(/[0-9]/);
  });
});
