<script setup lang="ts">
import { useI18n } from 'vue-i18n';
import { useLocaleStore } from '@/stores/locale';
import { isSupportedLocale } from '@/i18n';
import AppIcon from './AppIcon.vue';

const { t } = useI18n();
const locale = useLocaleStore();

function onChange(event: Event): void {
  const value = (event.target as HTMLSelectElement).value;
  if (isSupportedLocale(value)) {
    locale.set(value);
  }
}
</script>

<template>
  <label class="language-switcher">
    <AppIcon name="globe" :size="16" />
    <span class="visually-hidden">{{ t('app.language') }}</span>
    <select class="language-switcher__select" :value="locale.current" data-testid="language-select" @change="onChange">
      <option v-for="option in locale.options" :key="option.code" :value="option.code" :lang="option.code">
        {{ option.name }}
      </option>
    </select>
  </label>
</template>

<style scoped>
.language-switcher {
  display: inline-flex;
  align-items: center;
  gap: 0.35rem;
  color: var(--text-muted);
}

.language-switcher__select {
  border: 1px solid transparent;
  background: transparent;
  color: var(--text);
  padding: 0.35rem 0.4rem;
  border-radius: var(--radius-sm);
  cursor: pointer;
  max-width: 9rem;
}

.language-switcher__select:hover {
  border-color: var(--border);
  background: var(--surface-2);
}
</style>
