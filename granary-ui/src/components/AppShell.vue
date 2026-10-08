<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { useRoute } from 'vue-router';
import { SETTINGS_MANAGE, USERS_MANAGE, useAuthStore } from '@/stores/auth';
import { useSystemStore } from '@/stores/system';
import AppIcon from './AppIcon.vue';
import AppLogo from './AppLogo.vue';
import LanguageSwitcher from './LanguageSwitcher.vue';
import UserMenu from './UserMenu.vue';

const { t } = useI18n();
const auth = useAuthStore();
const system = useSystemStore();
const route = useRoute();
const menuOpen = ref(false);

interface NavItem {
  name: string;
  labelKey: string;
  icon: string;
  privilege?: string;
}

const MAIN_ITEMS: NavItem[] = [
  { name: 'dashboard', labelKey: 'nav.dashboard', icon: 'home' },
  { name: 'repositories', labelKey: 'nav.repositories', icon: 'database' },
  { name: 'search', labelKey: 'nav.search', icon: 'search' },
];

const ADMIN_ITEMS: NavItem[] = [
  { name: 'users', labelKey: 'nav.users', icon: 'users', privilege: USERS_MANAGE },
  { name: 'roles', labelKey: 'nav.roles', icon: 'shield', privilege: USERS_MANAGE },
  { name: 'settings', labelKey: 'nav.settings', icon: 'settings', privilege: SETTINGS_MANAGE },
];

const visible = (item: NavItem) => !item.privilege || auth.can(item.privilege);
const mainItems = computed(() => MAIN_ITEMS.filter(visible));
const adminItems = computed(() => ADMIN_ITEMS.filter(visible));

/** Sub-pages such as /repositories/:name keep their section highlighted. */
function isActive(name: string): boolean {
  return name === 'dashboard' ? route.path === '/' : route.path === `/${name}` || route.path.startsWith(`/${name}/`);
}

watch(
  () => route.fullPath,
  () => {
    menuOpen.value = false;
  },
);

void system.loadInfo();
</script>

<template>
  <div class="shell">
    <a class="skip-link" href="#main">{{ t('app.skipToContent') }}</a>
    <header class="shell__header">
      <button type="button" class="btn btn--ghost btn--icon menu-button" :aria-label="menuOpen ? t('app.closeMenu') : t('app.openMenu')" :aria-expanded="menuOpen" aria-controls="sidebar" @click="menuOpen = !menuOpen">
        <AppIcon :name="menuOpen ? 'close' : 'menu'" :size="20" />
      </button>
      <RouterLink class="shell__brand" :to="{ name: 'dashboard' }">
        <AppLogo :size="28" />
        <span>{{ t('app.name') }}</span>
      </RouterLink>
      <span class="shell__spacer"></span>
      <LanguageSwitcher />
      <UserMenu />
    </header>

    <div v-if="menuOpen" class="sidebar-backdrop" @click="menuOpen = false"></div>

    <aside id="sidebar" class="shell__sidebar" :class="{ 'shell__sidebar--open': menuOpen }">
      <nav class="nav" :aria-label="t('app.mainNavigation')">
        <RouterLink v-for="item in mainItems" :key="item.name" class="nav__link" :to="{ name: item.name }" :aria-current="isActive(item.name) ? 'page' : undefined">
          <AppIcon :name="item.icon" :size="18" />
          {{ t(item.labelKey) }}
        </RouterLink>
        <template v-if="adminItems.length">
          <div class="nav__section">{{ t('nav.administration') }}</div>
          <RouterLink v-for="item in adminItems" :key="item.name" class="nav__link" :to="{ name: item.name }" :aria-current="isActive(item.name) ? 'page' : undefined">
            <AppIcon :name="item.icon" :size="18" />
            {{ t(item.labelKey) }}
          </RouterLink>
        </template>
        <RouterLink class="nav__link" :to="{ name: 'account' }" :aria-current="isActive('account') ? 'page' : undefined">
          <AppIcon name="key" :size="18" />
          {{ t('nav.account') }}
        </RouterLink>
      </nav>
      <div class="shell__footer">
        <div>{{ t('app.name') }} · {{ t('app.version', { version: system.info?.version ?? '…' }) }}</div>
        <div v-if="system.info?.offlineMode" class="cluster" style="margin-top: 0.25rem; color: var(--warning)">
          <AppIcon name="cloudOff" :size="14" />
          {{ t('common.offline') }}
        </div>
      </div>
    </aside>

    <main id="main" class="shell__main" tabindex="-1">
      <div class="shell__content">
        <slot />
      </div>
    </main>
  </div>
</template>
