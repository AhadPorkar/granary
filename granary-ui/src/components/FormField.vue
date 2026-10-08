<script setup lang="ts">
import { computed } from 'vue';
import { useI18n } from 'vue-i18n';

/**
 * Label + control + hint + error with the ARIA wiring done once. The default slot receives
 * {id, invalid, describedBy} so any native control can be used inside.
 */
const props = withDefaults(defineProps<{ id: string; label: string; hint?: string; error?: string; required?: boolean; optional?: boolean }>(), {
  hint: undefined,
  error: undefined,
  required: false,
  optional: false,
});

const { t } = useI18n();
const hintId = computed(() => `${props.id}-hint`);
const errorId = computed(() => `${props.id}-error`);
const describedBy = computed(() => {
  const ids: string[] = [];
  if (props.hint) {
    ids.push(hintId.value);
  }
  if (props.error) {
    ids.push(errorId.value);
  }
  return ids.length ? ids.join(' ') : undefined;
});
</script>

<template>
  <div class="field" :data-field="id">
    <label class="field__label" :for="id">
      {{ label }}
      <span v-if="required" class="field__required" aria-hidden="true">*</span>
      <span v-if="optional" class="muted small"> ({{ t('common.optional') }})</span>
    </label>
    <slot :id="id" :invalid="Boolean(error)" :described-by="describedBy" />
    <div v-if="hint" :id="hintId" class="field__hint">{{ hint }}</div>
    <div v-if="error" :id="errorId" class="field__error" role="alert">{{ error }}</div>
  </div>
</template>
