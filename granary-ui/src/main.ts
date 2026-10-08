import { createApp } from 'vue';
import { createPinia } from 'pinia';
import App from './App.vue';
import { i18n } from './i18n';
import { createAppRouter } from './router';
import { onUnauthorized } from './api/client';
import { useAuthStore } from './stores/auth';
import { useLocaleStore } from './stores/locale';
import { useToastStore } from './stores/toast';
import './styles/main.css';

const app = createApp(App);
const pinia = createPinia();
app.use(pinia);
app.use(i18n);

const router = createAppRouter();
app.use(router);

useLocaleStore().init();

// An expired or revoked session: forget it and send the person back to the login page,
// remembering where they wanted to go.
onUnauthorized(() => {
  const auth = useAuthStore();
  const hadSession = auth.isAuthenticated;
  const wasRestored = auth.restored;
  auth.clearSession();
  if (!hadSession) {
    return;
  }
  useToastStore().info(i18n.global.t('auth.sessionExpired'));
  // During the initial restore the router guard is still running and redirects by itself.
  const current = router.currentRoute.value;
  if (wasRestored && !current.meta.public) {
    void router.replace({ name: 'login', query: current.fullPath !== '/' ? { redirect: current.fullPath } : {} });
  }
});

app.mount('#app');
