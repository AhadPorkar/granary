<script setup lang="ts">
import { computed } from 'vue';
import { useI18n } from 'vue-i18n';
import CopyButton from './CopyButton.vue';

/**
 * A snippet of configuration or a command line with a copy button. Code is left-to-right whatever
 * the UI language, hence the explicit dir attribute on the block. The copy button sits in the header
 * row, not on top of the code, so it never hides the end of a long line on a narrow screen.
 */
const props = withDefaults(defineProps<{ code: string; title?: string; filename?: string | null; language?: string | null }>(), {
  title: undefined,
  filename: null,
  language: null,
});

const { t } = useI18n();

/** "Copy “pip.conf”" tells a screen reader which of the many copy buttons on the page this is. */
const copyLabel = computed(() => {
  const name = props.title || props.filename;
  return name ? t('connect.copySnippet', { name }) : t('common.copyToClipboard');
});
</script>

<template>
  <section class="code-section">
    <div class="code-section__header">
      <h3 v-if="title" class="code-section__title"><bdi>{{ title }}</bdi></h3>
      <code v-if="filename" class="code-section__file ltr" data-testid="code-filename">{{ filename }}</code>
      <span v-if="language" class="code-section__language" :title="t('connect.language', { language })" :aria-label="t('connect.language', { language })" data-testid="code-language">{{ language }}</span>
      <span class="code-section__copy"><CopyButton :text="code" :label="copyLabel" show-label /></span>
    </div>
    <pre class="code-block" tabindex="0" dir="ltr" :data-language="language ?? undefined"><code>{{ code }}</code></pre>
  </section>
</template>

<style scoped>
.code-section {
  display: flex;
  flex-direction: column;
  gap: 0.4rem;
  min-width: 0;
}

.code-section__header {
  display: flex;
  flex-wrap: wrap;
  gap: 0.35rem 0.5rem;
  align-items: center;
  min-width: 0;
}

.code-section__title {
  font-size: 0.875rem;
  font-weight: 600;
  min-width: 0;
  overflow-wrap: anywhere;
}

.code-section__file {
  font-weight: 500;
  color: var(--text-muted);
  background: var(--surface-2);
  padding: 0.05rem 0.4rem;
  border-radius: 4px;
  min-width: 0;
  /* long paths such as /etc/containerd/certs.d/<host>/hosts.toml wrap instead of leaving the card */
  overflow-wrap: anywhere;
}

.code-section__language {
  font-size: 0.7rem;
  font-weight: 600;
  letter-spacing: 0.04em;
  text-transform: uppercase;
  color: var(--text-muted);
  border: 1px solid var(--border);
  padding: 0.05rem 0.4rem;
  border-radius: 1rem;
  direction: ltr;
}

.code-section__copy {
  margin-inline-start: auto;
}
</style>
