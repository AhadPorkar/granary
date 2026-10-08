import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router';
import { REPOSITORIES_MANAGE, SETTINGS_MANAGE, USERS_MANAGE, useAuthStore } from '@/stores/auth';
import { i18n } from '@/i18n';

declare module 'vue-router' {
  interface RouteMeta {
    /** Reachable without a session (the login page). */
    public?: boolean;
    /** Required privilege; `system:admin` always passes. */
    privilege?: string;
    /** i18n key of the document title. */
    titleKey?: string;
  }
}

export const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'login',
    component: () => import('@/views/LoginView.vue'),
    meta: { public: true, titleKey: 'auth.signIn' },
  },
  {
    path: '/',
    name: 'dashboard',
    component: () => import('@/views/DashboardView.vue'),
    meta: { titleKey: 'nav.dashboard' },
  },
  {
    path: '/repositories',
    name: 'repositories',
    component: () => import('@/views/RepositoriesView.vue'),
    meta: { titleKey: 'nav.repositories' },
  },
  {
    path: '/repositories/new',
    name: 'repository-new',
    component: () => import('@/views/RepositoryEditView.vue'),
    meta: { privilege: REPOSITORIES_MANAGE, titleKey: 'repositories.new' },
  },
  {
    path: '/repositories/:name',
    name: 'repository',
    component: () => import('@/views/RepositoryDetailView.vue'),
    props: true,
    meta: { titleKey: 'nav.repositories' },
  },
  {
    path: '/repositories/:name/edit',
    name: 'repository-edit',
    component: () => import('@/views/RepositoryEditView.vue'),
    props: true,
    meta: { privilege: REPOSITORIES_MANAGE, titleKey: 'repositories.edit' },
  },
  {
    path: '/search',
    name: 'search',
    component: () => import('@/views/SearchView.vue'),
    meta: { titleKey: 'nav.search' },
  },
  {
    path: '/users',
    name: 'users',
    component: () => import('@/views/UsersView.vue'),
    meta: { privilege: USERS_MANAGE, titleKey: 'nav.users' },
  },
  {
    path: '/roles',
    name: 'roles',
    component: () => import('@/views/RolesView.vue'),
    meta: { privilege: USERS_MANAGE, titleKey: 'nav.roles' },
  },
  {
    path: '/account',
    name: 'account',
    component: () => import('@/views/AccountView.vue'),
    meta: { titleKey: 'nav.account' },
  },
  {
    path: '/settings',
    name: 'settings',
    component: () => import('@/views/SettingsView.vue'),
    meta: { privilege: SETTINGS_MANAGE, titleKey: 'nav.settings' },
  },
  {
    path: '/forbidden',
    name: 'forbidden',
    component: () => import('@/views/NotFoundView.vue'),
    props: { kind: 'forbidden' },
    meta: { titleKey: 'forbidden.title' },
  },
  {
    path: '/:pathMatch(.*)*',
    name: 'not-found',
    component: () => import('@/views/NotFoundView.vue'),
    props: { kind: 'notFound' },
    meta: { titleKey: 'notFound.title' },
  },
];

export function createAppRouter() {
  const router = createRouter({
    history: createWebHistory(),
    routes,
    scrollBehavior: () => ({ top: 0 }),
  });

  router.beforeEach(async (to) => {
    const auth = useAuthStore();
    await auth.restore();
    if (to.meta.public) {
      // A signed-in user has no business on the login page.
      return auth.isAuthenticated && to.name === 'login' ? { name: 'dashboard' } : true;
    }
    if (!auth.isAuthenticated) {
      return { name: 'login', query: to.fullPath !== '/' ? { redirect: to.fullPath } : {} };
    }
    if (to.meta.privilege && !auth.can(to.meta.privilege)) {
      return { name: 'forbidden' };
    }
    return true;
  });

  router.afterEach((to) => {
    const t = i18n.global.t;
    const title = to.meta.titleKey ? t(to.meta.titleKey) : '';
    const name = typeof to.params.name === 'string' ? to.params.name : '';
    document.title = [name, title, t('app.name')].filter(Boolean).join(' · ');
  });

  return router;
}
