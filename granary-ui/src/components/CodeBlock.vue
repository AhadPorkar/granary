<script setup lang="ts">
import { useI18n } from 'vue-i18n';
import CopyButton from './CopyButton.vue';

/**
 * A snippet of configuration or a command line with a copy button. Code is left-to-right whatever
 * the UI language, hence the explicit dir attribute on the block.
 */
withDefaults(defineProps<{ code: string; title?: string; filename?: string | null; language?: string | null }>(), {
  title: undefined,
  filename: null,
  language: null,
});

const { t } = useI18n();
</script>

<template>
  <section class="code-section">
    <h3 v-if="title" class="code-section__title">
      <span>{{ title }}</span>
      <code v-if="filename" class="code-section__file ltr" data-testid="code-filename">{{ filename }}</code>
      <span v-if="language" class="code-section__language" :title="t('connect.language', { language })" :aria-label="t('connect.language', { language })" data-testid="code-language">{{ language }}</span>
    </h3>
    <pre class="code-block" tabindex="0" dir="ltr" :data-language="language ?? undefined"><code>{{ code }}</code><span class="code-block__copy"><CopyButton :text="code" /></span></pre>
  </section>
</template>

<style scoped>
.code-section {
  display: flex;
  flex-direction: column;
  gap: 0.4rem;
}

.code-section__title {
  font-size: 0.875rem;
  font-weight: 600;
  display: flex;
  flex-wrap: wrap;
  gap: 0.5rem;
  align-items: baseline;
}

.code-section__file {
  font-weight: 500;
  color: var(--text-muted);
  background: var(--surface-2);
  padding: 0.05rem 0.4rem;
  border-radius: 4px;
}

.code-section__language {
  font-size: 0.7rem;
  font-weight: 600;
  letter-spacing: 0.04em;
  text-transform: uppercase;
  color: var(--text-faint);
  border: 1px solid var(--border);
  padding: 0.05rem 0.4rem;
  border-radius: 1rem;
  direction: ltr;
}
</style>
