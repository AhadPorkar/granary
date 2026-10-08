<script setup lang="ts">
import CopyButton from './CopyButton.vue';

withDefaults(defineProps<{ code: string; title?: string; filename?: string }>(), { title: undefined, filename: undefined });
</script>

<template>
  <section class="code-section">
    <h3 v-if="title" class="code-section__title">
      {{ title }}
      <code v-if="filename" class="code-section__file">{{ filename }}</code>
    </h3>
    <pre class="code-block" tabindex="0"><code>{{ code }}</code><span class="code-block__copy"><CopyButton :text="code" /></span></pre>
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
</style>
