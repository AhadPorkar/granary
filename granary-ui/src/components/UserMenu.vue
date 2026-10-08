<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue';
import { useI18n } from 'vue-i18n';
import { useRouter } from 'vue-router';
import { useAuthStore } from '@/stores/auth';
import { useToastStore } from '@/stores/toast';
import AppIcon from './AppIcon.vue';

const { t } = useI18n();
const auth = useAuthStore();
const toast = useToastStore();
const router = useRouter();
const details = ref<HTMLDetailsElement | null>(null);

function close(): void {
  if (details.value) {
    details.value.open = false;
  }
}

function onDocumentClick(event: MouseEvent): void {
  if (details.value && !details.value.contains(event.target as Node)) {
    close();
  }
}

function onKeydown(event: KeyboardEvent): void {
  if (event.key === 'Escape') {
    close();
  }
}

async function logout(): Promise<void> {
  close();
  auth.logout();
  toast.info(t('auth.signedOut'));
  await router.push({ name: 'login' });
}

onMounted(() => {
  document.addEventListener('click', onDocumentClick);
  document.addEventListener('keydown', onKeydown);
});

onBeforeUnmount(() => {
  document.removeEventListener('click', onDocumentClick);
  document.removeEventListener('keydown', onKeydown);
});
</script>

<template>
  <details ref="details" class="details-menu">
    <summary class="btn btn--ghost" :aria-label="t('app.userMenu')">
      <AppIcon name="user" :size="16" />
      <span class="user-menu__name truncate">{{ auth.displayName }}</span>
      <AppIcon name="chevronDown" :size="14" />
    </summary>
    <div class="details-menu__panel">
      <div class="details-menu__header small">
        <div class="muted">{{ t('app.signedInAs') }}</div>
        <div class="truncate"><strong>{{ auth.username }}</strong></div>
      </div>
      <RouterLink class="details-menu__item" :to="{ name: 'account' }" @click="close">
        <AppIcon name="key" :size="16" />
        {{ t('nav.account') }}
      </RouterLink>
      <button type="button" class="details-menu__item" @click="logout">
        <AppIcon name="logout" :size="16" />
        {{ t('nav.logout') }}
      </button>
    </div>
  </details>
</template>

<style scoped>
.user-menu__name {
  max-width: 10rem;
}

@media (max-width: 40rem) {
  .user-menu__name {
    display: none;
  }
}
</style>
