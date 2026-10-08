<script setup lang="ts">
withDefaults(defineProps<{ modelValue: boolean; label: string; hint?: string; disabled?: boolean; id?: string }>(), {
  hint: undefined,
  disabled: false,
  id: undefined,
});
const emit = defineEmits<{ 'update:modelValue': [value: boolean] }>();
</script>

<template>
  <label class="switch" :class="{ 'switch--disabled': disabled }">
    <input
      :id="id"
      type="checkbox"
      role="switch"
      :checked="modelValue"
      :aria-checked="modelValue"
      :disabled="disabled"
      @change="emit('update:modelValue', ($event.target as HTMLInputElement).checked)"
    />
    <span class="switch__track" aria-hidden="true"></span>
    <span class="checkbox__text">
      <span>{{ label }}</span>
      <span v-if="hint" class="field__hint">{{ hint }}</span>
    </span>
  </label>
</template>
