/* Shared Vitest setup: jsdom lacks a few browser APIs the components touch. */
import { afterEach } from 'vitest';
import { i18n, applyDocumentLocale } from '@/i18n';

if (typeof HTMLDialogElement !== 'undefined') {
  const proto = HTMLDialogElement.prototype as HTMLDialogElement & { showModal?: () => void; close?: () => void };
  if (typeof proto.showModal !== 'function') {
    proto.showModal = function showModal(this: HTMLDialogElement) {
      this.setAttribute('open', '');
    };
  }
  if (typeof proto.close !== 'function') {
    proto.close = function close(this: HTMLDialogElement) {
      this.removeAttribute('open');
      this.dispatchEvent(new Event('close'));
    };
  }
}

afterEach(() => {
  // Every test starts from the English, left-to-right baseline with empty storage.
  i18n.global.locale.value = 'en';
  applyDocumentLocale('en');
  try {
    localStorage.clear();
  } catch {
    /* ignore */
  }
});
