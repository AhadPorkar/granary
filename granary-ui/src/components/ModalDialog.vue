<script setup lang="ts">
import { nextTick, onBeforeUnmount, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import AppIcon from './AppIcon.vue';

/**
 * A native <dialog>, so focus trapping, Escape and the backdrop come from the browser.
 * Falls back to the plain `open` attribute where showModal() is unavailable (older browsers, jsdom).
 */
const props = withDefaults(defineProps<{ open: boolean; title: string; wide?: boolean }>(), { wide: false });
const emit = defineEmits<{ close: [] }>();

const { t } = useI18n();
const dialog = ref<HTMLDialogElement | null>(null);

function show(): void {
  const el = dialog.value;
  if (!el || el.open) {
    return;
  }
  if (typeof el.showModal === 'function') {
    el.showModal();
  } else {
    el.setAttribute('open', '');
  }
  void nextTick(() => {
    const focusable = el.querySelector<HTMLElement>('[autofocus], input, select, textarea, button:not([data-dialog-close])');
    focusable?.focus();
  });
}

function hide(): void {
  const el = dialog.value;
  if (!el || !el.open) {
    return;
  }
  if (typeof el.close === 'function') {
    el.close();
  } else {
    el.removeAttribute('open');
  }
}

function onCancel(event: Event): void {
  event.preventDefault();
  emit('close');
}

watch(
  () => props.open,
  (open) => {
    void nextTick(() => (open ? show() : hide()));
  },
  { immediate: true },
);

onBeforeUnmount(hide);
</script>

<template>
  <dialog ref="dialog" class="dialog" :class="{ 'dialog--wide': wide }" :aria-label="title" @cancel="onCancel" @close="emit('close')">
    <div v-if="open" class="dialog__header">
      <h2>{{ title }}</h2>
      <button type="button" class="btn btn--ghost btn--icon" data-dialog-close :aria-label="t('common.close')" @click="emit('close')">
        <AppIcon name="close" :size="18" />
      </button>
    </div>
    <div v-if="open" class="dialog__body">
      <slot />
    </div>
    <div v-if="open && $slots.footer" class="dialog__footer">
      <slot name="footer" />
    </div>
  </dialog>
</template>
