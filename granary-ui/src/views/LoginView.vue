<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { useI18n } from 'vue-i18n';
import { useRoute, useRouter } from 'vue-router';
import { useAuthStore } from '@/stores/auth';
import { useSystemStore } from '@/stores/system';
import AppLogo from '@/components/AppLogo.vue';
import AppIcon from '@/components/AppIcon.vue';
import ErrorAlert from '@/components/ErrorAlert.vue';
import FormField from '@/components/FormField.vue';
import LanguageSwitcher from '@/components/LanguageSwitcher.vue';

const { t } = useI18n();
const auth = useAuthStore();
const system = useSystemStore();
const router = useRouter();
const route = useRoute();

const username = ref('');
const password = ref('');
const showPassword = ref(false);
const busy = ref(false);
const error = ref<unknown>(null);

onMounted(() => {
  void system.loadInfo();
});

async function submit(): Promise<void> {
  if (!username.value || !password.value) {
    return;
  }
  busy.value = true;
  error.value = null;
  try {
    await auth.login(username.value, password.value);
    const redirect = route.query.redirect;
    const target = typeof redirect === 'string' && redirect.startsWith('/') && !redirect.startsWith('//') ? redirect : '/';
    await router.replace(target);
  } catch (e) {
    error.value = e;
    password.value = '';
  } finally {
    busy.value = false;
  }
}
</script>

<template>
  <div class="login">
    <div class="card login__card">
      <div class="card__body">
        <div class="login__brand">
          <AppLogo :size="52" />
          <h1>{{ system.info?.name ?? t('app.name') }}</h1>
          <p class="muted small" style="margin: 0">
            {{ t('app.tagline') }}
            <template v-if="system.info?.version"> · {{ t('app.version', { version: system.info.version }) }}</template>
          </p>
        </div>

        <form class="stack" novalidate @submit.prevent="submit">
          <h2 class="visually-hidden">{{ t('auth.signInTo', { name: system.info?.name ?? t('app.name') }) }}</h2>
          <ErrorAlert :error="error" />

          <FormField id="login-username" :label="t('auth.username')" required>
            <template #default="{ id }">
              <input :id="id" v-model="username" class="input" type="text" autocomplete="username" autocapitalize="none" spellcheck="false" required autofocus />
            </template>
          </FormField>

          <FormField id="login-password" :label="t('auth.password')" required>
            <template #default="{ id }">
              <div class="input-group">
                <input :id="id" v-model="password" class="input" :type="showPassword ? 'text' : 'password'" autocomplete="current-password" required />
                <button type="button" class="btn btn--icon" :aria-label="showPassword ? t('common.hidePassword') : t('common.showPassword')" :aria-pressed="showPassword" @click="showPassword = !showPassword">
                  <AppIcon :name="showPassword ? 'eyeOff' : 'eye'" :size="18" />
                </button>
              </div>
            </template>
          </FormField>

          <button type="submit" class="btn btn--primary btn--block" :disabled="busy || !username || !password">
            <span v-if="busy" class="spinner" aria-hidden="true"></span>
            {{ busy ? t('auth.signingIn') : t('auth.signIn') }}
          </button>
        </form>

        <div class="login__lang">
          <LanguageSwitcher />
        </div>
      </div>
    </div>
  </div>
</template>
