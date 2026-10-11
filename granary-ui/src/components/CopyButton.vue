<script setup lang="ts">
import { onBeforeUnmount, ref } from 'vue';
import { useI18n } from 'vue-i18n';
import AppIcon from './AppIcon.vue';

/**
 * Copies `text`. `label` names the action for assistive technology (and is the tooltip unless `tooltip`
 * is given, e.g. the value itself); `showLabel` adds a visible "Copy"/"Copied" text next to the icon.
 */
const props = withDefaults(defineProps<{ text: string; label?: string; tooltip?: string; small?: boolean; showLabel?: boolean }>(), {
  label: undefined,
  tooltip: undefined,
  small: true,
  showLabel: false,
});

const { t } = useI18n();
const copied = ref(false);
let timer: ReturnType<typeof setTimeout> | undefined;

async function copy(): Promise<void> {
  const ok = await writeClipboard(props.text);
  if (ok) {
    copied.value = true;
    clearTimeout(timer);
    timer = setTimeout(() => (copied.value = false), 1800);
  }
}

async function writeClipboard(text: string): Promise<boolean> {
  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(text);
      return true;
    }
  } catch {
    /* fall through to the legacy path */
  }
  try {
    const area = document.createElement('textarea');
    area.value = text;
    area.setAttribute('readonly', '');
    area.style.position = 'fixed';
    area.style.opacity = '0';
    document.body.appendChild(area);
    area.select();
    const ok = document.execCommand('copy');
    document.body.removeChild(area);
    return ok;
  } catch {
    return false;
  }
}

onBeforeUnmount(() => clearTimeout(timer));
</script>

<template>
  <button
    type="button"
    class="btn btn--ghost"
    :class="{ 'btn--sm': small, 'btn--icon': !showLabel }"
    :aria-label="label ?? t('common.copyToClipboard')"
    :title="tooltip ?? label ?? t('common.copyToClipboard')"
    @click="copy"
  >
    <AppIcon :name="copied ? 'check' : 'copy'" :size="16" />
    <span v-if="showLabel">{{ copied ? t('common.copied') : t('common.copy') }}</span>
    <span v-else-if="copied" class="visually-hidden" role="status">{{ t('common.copied') }}</span>
  </button>
</template>
