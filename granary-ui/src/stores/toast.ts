import { ref } from 'vue';
import { defineStore } from 'pinia';

export type ToastKind = 'success' | 'error' | 'info';

export interface Toast {
  id: number;
  kind: ToastKind;
  message: string;
}

let nextId = 1;

export const useToastStore = defineStore('toast', () => {
  const toasts = ref<Toast[]>([]);

  function dismiss(id: number): void {
    toasts.value = toasts.value.filter((t) => t.id !== id);
  }

  function push(kind: ToastKind, message: string, ttl = 4500): number {
    const id = nextId++;
    toasts.value.push({ id, kind, message });
    if (ttl > 0 && typeof setTimeout === 'function') {
      setTimeout(() => dismiss(id), ttl);
    }
    return id;
  }

  return {
    toasts,
    push,
    dismiss,
    success: (message: string) => push('success', message),
    error: (message: string) => push('error', message, 7000),
    info: (message: string) => push('info', message),
  };
});
