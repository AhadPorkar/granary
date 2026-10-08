<script setup lang="ts">
withDefaults(defineProps<{ title: string; text?: string }>(), { text: undefined });
</script>

<template>
  <div class="empty">
    <div class="empty__title">{{ title }}</div>
    <div v-if="text" class="small">{{ text }}</div>
    <div v-if="$slots.default" class="cluster" style="justify-content: center; margin-top: 0.75rem">
      <slot />
    </div>
  </div>
</template>
