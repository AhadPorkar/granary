<script setup lang="ts">
import { computed, useId } from 'vue';

/**
 * A checkbox styled as a switch. The label text alone names it and the hint describes it
 * (aria-describedby), so a screen reader does not read the hint as part of the name.
 */
const props = withDefaults(defineProps<{ modelValue: boolean; label: string; hint?: string; disabled?: boolean; id?: string }>(), {
  hint: undefined,
  disabled: false,
  id: undefined,
});
const emit = defineEmits<{ 'update:modelValue': [value: boolean] }>();

const uid = useId();
const inputId = computed(() => props.id ?? `switch-${uid}`);
const labelId = computed(() => `${inputId.value}-label`);
const hintId = computed(() => `${inputId.value}-hint`);
</script>

<template>
  <label class="switch" :class="{ 'switch--disabled': disabled }">
    <input
      :id="inputId"
      type="checkbox"
      role="switch"
      :checked="modelValue"
      :disabled="disabled"
      :aria-labelledby="labelId"
      :aria-describedby="hint ? hintId : undefined"
      @change="emit('update:modelValue', ($event.target as HTMLInputElement).checked)"
    />
    <span class="switch__track" aria-hidden="true"></span>
    <span class="checkbox__text">
      <span :id="labelId">{{ label }}</span>
      <span v-if="hint" :id="hintId" class="field__hint">{{ hint }}</span>
    </span>
  </label>
</template>
