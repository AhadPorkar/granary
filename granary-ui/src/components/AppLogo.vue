<script setup lang="ts">
withDefaults(defineProps<{ size?: number | string }>(), { size: 28 });
</script>

<template>
  <!-- A stylised grain silo with a roof and a door: the Granary mark. -->
  <svg
    class="logo"
    :width="size"
    :height="size"
    viewBox="0 0 32 32"
    role="img"
    aria-label="Granary"
  >
    <rect width="32" height="32" rx="7" fill="var(--primary)" />
    <path d="M8 13 L16 6 L24 13 Z" fill="#fde68a" />
    <rect x="10" y="13" width="12" height="13" rx="1.5" fill="#fef3c7" />
    <path d="M13 26 V19 a3 3 0 0 1 6 0 V26 Z" fill="var(--primary)" />
    <path d="M10 17 H22 M10 21 H22" stroke="#d97706" stroke-width="1.2" />
  </svg>
</template>

<style scoped>
.logo {
  flex-shrink: 0;
}
</style>
