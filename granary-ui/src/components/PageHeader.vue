<script setup lang="ts">
withDefaults(defineProps<{ title: string; subtitle?: string }>(), { subtitle: undefined });
</script>

<template>
  <header class="page-header">
    <div class="page-header__text">
      <h1>{{ title }}</h1>
      <p v-if="subtitle || $slots.subtitle" class="page-header__subtitle"><slot name="subtitle">{{ subtitle }}</slot></p>
    </div>
    <div v-if="$slots.default" class="page-header__actions">
      <slot />
    </div>
  </header>
</template>
