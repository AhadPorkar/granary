<script setup lang="ts">
withDefaults(defineProps<{ variant?: 'neutral' | 'success' | 'danger' | 'warning' | 'info'; plain?: boolean }>(), {
  variant: 'neutral',
  plain: false,
});
</script>

<template>
  <span class="badge" :class="[variant !== 'neutral' ? `badge--${variant}` : '', plain ? 'badge--plain' : '']">
    <slot />
  </span>
</template>
