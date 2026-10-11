<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import { useI18n } from 'vue-i18n';
import { api } from '@/api';
import type { TokenView } from '@/api/types';
import { useAuthStore } from '@/stores/auth';
import { useToastStore } from '@/stores/toast';
import { useApiError } from '@/composables/useApiError';
import { useFormatters } from '@/composables/useFormatters';
import { fromDateTimeLocal } from '@/utils/format';
import AppIcon from '@/components/AppIcon.vue';
import ConfirmDialog from '@/components/ConfirmDialog.vue';
import CopyButton from '@/components/CopyButton.vue';
import EmptyState from '@/components/EmptyState.vue';
import ErrorAlert from '@/components/ErrorAlert.vue';
import FormField from '@/components/FormField.vue';
import LoadingIndicator from '@/components/LoadingIndicator.vue';
import ModalDialog from '@/components/ModalDialog.vue';
import PageHeader from '@/components/PageHeader.vue';
import StatusBadge from '@/components/StatusBadge.vue';

const MIN_PASSWORD = 8;

const { t } = useI18n();
const auth = useAuthStore();
const toast = useToastStore();
const apiError = useApiError();
const fmt = useFormatters();

// --- password ---
const password = reactive({ current: '', next: '', confirm: '' });
const passwordError = ref<unknown>(null);
const passwordLocal = ref<Record<string, string>>({});
const passwordSaving = ref(false);
const passwordFields = computed(() => ({ ...apiError.fields(passwordError.value), ...passwordLocal.value }));
const passwordGeneral = computed(() => (passwordError.value && !Object.keys(apiError.fields(passwordError.value)).length ? passwordError.value : null));

async function changePassword(): Promise<void> {
  const found: Record<string, string> = {};
  if (!password.current) {
    found.currentPassword = t('validation.required');
  }
  if (password.next.length < MIN_PASSWORD) {
    found.newPassword = t('validation.minLength', { min: fmt.number(MIN_PASSWORD) });
  }
  if (password.next !== password.confirm) {
    found.confirmPassword = t('validation.passwordMismatch');
  }
  passwordLocal.value = found;
  if (Object.keys(found).length) {
    return;
  }
  passwordSaving.value = true;
  passwordError.value = null;
  try {
    await api.auth.changePassword(password.current, password.next);
    Object.assign(password, { current: '', next: '', confirm: '' });
    toast.success(t('account.passwordChanged'));
  } catch (e) {
    passwordError.value = e;
  } finally {
    passwordSaving.value = false;
  }
}

// --- tokens ---
const tokens = ref<TokenView[]>([]);
const tokensLoading = ref(true);
const tokensError = ref<unknown>(null);
const createOpen = ref(false);
const createError = ref<unknown>(null);
const creating = ref(false);
const tokenForm = reactive({ name: '', expiresAt: '' });
const tokenLocal = ref<Record<string, string>>({});
const tokenFields = computed(() => ({ ...apiError.fields(createError.value), ...tokenLocal.value }));
const tokenGeneral = computed(() => (createError.value && !Object.keys(apiError.fields(createError.value)).length ? createError.value : null));
const issuedSecret = ref<{ name: string; secret: string } | null>(null);
const pendingRevoke = ref<TokenView | null>(null);
const revoking = ref(false);

async function loadTokens(): Promise<void> {
  tokensLoading.value = true;
  tokensError.value = null;
  try {
    tokens.value = await api.tokens.list();
  } catch (e) {
    tokensError.value = e;
  } finally {
    tokensLoading.value = false;
  }
}

function openCreate(): void {
  Object.assign(tokenForm, { name: '', expiresAt: '' });
  tokenLocal.value = {};
  createError.value = null;
  createOpen.value = true;
}

async function createToken(): Promise<void> {
  const found: Record<string, string> = {};
  if (!tokenForm.name.trim()) {
    found.name = t('validation.required');
  }
  const expiresAt = fromDateTimeLocal(tokenForm.expiresAt);
  if (tokenForm.expiresAt && (!expiresAt || new Date(expiresAt).getTime() <= Date.now())) {
    found.expiresAt = t('validation.futureDate');
  }
  tokenLocal.value = found;
  if (Object.keys(found).length) {
    return;
  }
  creating.value = true;
  createError.value = null;
  try {
    const issued = await api.tokens.create(tokenForm.name.trim(), expiresAt);
    tokens.value = [issued.token, ...tokens.value];
    createOpen.value = false;
    issuedSecret.value = { name: issued.token.name, secret: issued.secret };
  } catch (e) {
    createError.value = e;
  } finally {
    creating.value = false;
  }
}

async function confirmRevoke(): Promise<void> {
  const token = pendingRevoke.value;
  if (!token) {
    return;
  }
  revoking.value = true;
  try {
    await api.tokens.revoke(token.id);
    tokens.value = tokens.value.filter((x) => x.id !== token.id);
    toast.success(t('account.tokens.revoked', { name: token.name }));
    pendingRevoke.value = null;
  } catch (e) {
    toast.error(apiError.message(e));
  } finally {
    revoking.value = false;
  }
}

function isExpired(token: TokenView): boolean {
  return Boolean(token.expiresAt) && new Date(token.expiresAt as string).getTime() < Date.now();
}

onMounted(loadTokens);
</script>

<template>
  <div>
    <PageHeader :title="t('account.title')" :subtitle="t('account.subtitle')" />

    <div class="grid grid--2">
      <section class="card">
        <div class="card__header"><h2>{{ t('account.profile') }}</h2></div>
        <div class="card__body">
          <dl class="kv">
            <dt>{{ t('account.username') }}</dt>
            <dd><strong>{{ auth.username }}</strong></dd>
            <template v-if="auth.displayName && auth.displayName !== auth.username">
              <dt>{{ t('users.fields.displayName') }}</dt>
              <dd>{{ auth.displayName }}</dd>
            </template>
            <dt>{{ t('account.roles') }}</dt>
            <dd class="cluster">
              <StatusBadge v-for="role in auth.roles" :key="role" plain variant="info">{{ role }}</StatusBadge>
              <span v-if="!auth.roles.length" class="faint">—</span>
            </dd>
          </dl>
        </div>
      </section>

      <section class="card">
        <div class="card__header"><h2>{{ t('account.changePassword') }}</h2></div>
        <form class="card__body stack" novalidate @submit.prevent="changePassword">
          <ErrorAlert :error="passwordGeneral" />
          <FormField id="pw-current" :label="t('account.currentPassword')" :error="passwordFields.currentPassword" required>
            <template #default="{ id, invalid, describedBy }">
              <input :id="id" v-model="password.current" class="input" type="password" autocomplete="current-password" required :aria-invalid="invalid" :aria-describedby="describedBy" />
            </template>
          </FormField>
          <FormField id="pw-new" :label="t('account.newPassword')" :hint="t('users.hints.password', { min: fmt.number(MIN_PASSWORD) })" :error="passwordFields.newPassword ?? passwordFields.password" required>
            <template #default="{ id, invalid, describedBy }">
              <input :id="id" v-model="password.next" class="input" type="password" autocomplete="new-password" required :minlength="MIN_PASSWORD" :aria-invalid="invalid" :aria-describedby="describedBy" />
            </template>
          </FormField>
          <FormField id="pw-confirm" :label="t('account.confirmPassword')" :error="passwordFields.confirmPassword" required>
            <template #default="{ id, invalid, describedBy }">
              <input :id="id" v-model="password.confirm" class="input" type="password" autocomplete="new-password" required :aria-invalid="invalid" :aria-describedby="describedBy" />
            </template>
          </FormField>
          <div class="form-actions" style="border: 0; padding: 0">
            <button type="submit" class="btn btn--primary" :disabled="passwordSaving">
              <span v-if="passwordSaving" class="spinner" aria-hidden="true"></span>
              {{ t('account.changePassword') }}
            </button>
          </div>
        </form>
      </section>
    </div>

    <section class="card" style="margin-top: 1rem">
      <div class="card__header">
        <div>
          <h2>{{ t('account.tokens.title') }}</h2>
          <p class="muted small" style="margin: 0.25rem 0 0">{{ t('account.tokens.intro') }}</p>
        </div>
        <button type="button" class="btn btn--primary" @click="openCreate">
          <AppIcon name="plus" :size="16" />
          {{ t('account.tokens.new') }}
        </button>
      </div>
      <ErrorAlert :error="tokensError" />
      <LoadingIndicator v-if="tokensLoading" />
      <EmptyState v-else-if="!tokens.length" :title="t('account.tokens.empty')" />
      <div v-else class="table-wrap">
        <table class="table table--stack">
          <thead>
            <tr>
              <th scope="col">{{ t('account.tokens.name') }}</th>
              <th scope="col">{{ t('account.tokens.prefix') }}</th>
              <th scope="col">{{ t('account.tokens.created') }}</th>
              <th scope="col">{{ t('account.tokens.expiresAt') }}</th>
              <th scope="col">{{ t('account.tokens.lastUsed') }}</th>
              <th scope="col" class="actions"><span class="visually-hidden">{{ t('common.actions') }}</span></th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="token in tokens" :key="token.id">
              <td><strong>{{ token.name }}</strong></td>
              <td><code class="ltr">{{ token.prefix }}…</code></td>
              <td class="nowrap muted small" :data-label="t('account.tokens.created')">{{ fmt.dateTime(token.createdAt) }}</td>
              <td class="nowrap small" :data-label="t('account.tokens.expiresAt')">
                <StatusBadge v-if="isExpired(token)" variant="danger">{{ t('account.tokens.expired') }}</StatusBadge>
                <span v-else-if="token.expiresAt">{{ fmt.dateTime(token.expiresAt) }}</span>
                <span v-else class="muted">{{ t('account.tokens.neverExpires') }}</span>
              </td>
              <td class="nowrap muted small" :data-label="t('account.tokens.lastUsed')">{{ token.lastUsedAt ? fmt.relative(token.lastUsedAt) : t('common.never') }}</td>
              <td class="actions">
                <button type="button" class="btn btn--danger-ghost btn--sm" :aria-label="t('account.tokens.revokeName', { name: token.name })" @click="pendingRevoke = token">
                  <AppIcon name="trash" :size="15" />
                  {{ t('account.tokens.revoke') }}
                </button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>

    <ModalDialog :open="createOpen" :title="t('account.tokens.create')" @close="creating || (createOpen = false)">
      <form id="token-form" class="stack" novalidate @submit.prevent="createToken">
        <ErrorAlert :error="tokenGeneral" />
        <FormField id="token-name" :label="t('account.tokens.name')" :error="tokenFields.name" required>
          <template #default="{ id, invalid, describedBy }">
            <input :id="id" v-model="tokenForm.name" class="input" type="text" maxlength="200" required :placeholder="t('account.tokens.namePlaceholder')" :aria-invalid="invalid" :aria-describedby="describedBy" />
          </template>
        </FormField>
        <FormField id="token-expires" :label="t('account.tokens.expiresAt')" :hint="t('account.tokens.expiresOptional')" :error="tokenFields.expiresAt" optional>
          <template #default="{ id, invalid, describedBy }">
            <input :id="id" v-model="tokenForm.expiresAt" class="input" type="datetime-local" :aria-invalid="invalid" :aria-describedby="describedBy" />
          </template>
        </FormField>
      </form>
      <template #footer>
        <button type="button" class="btn" :disabled="creating" @click="createOpen = false">{{ t('common.cancel') }}</button>
        <button type="submit" form="token-form" class="btn btn--primary" :disabled="creating">
          <span v-if="creating" class="spinner" aria-hidden="true"></span>
          {{ t('common.create') }}
        </button>
      </template>
    </ModalDialog>

    <ModalDialog :open="issuedSecret !== null" :title="t('account.tokens.secretTitle')" @close="issuedSecret = null">
      <div v-if="issuedSecret" class="stack">
        <div class="alert alert--warning">
          <AppIcon class="alert__icon" name="alert" :size="18" />
          <div class="alert__body">{{ t('account.tokens.secretHint') }}</div>
        </div>
        <div class="secret-box">
          <code data-testid="token-secret">{{ issuedSecret.secret }}</code>
          <CopyButton :text="issuedSecret.secret" :small="false" show-label />
        </div>
      </div>
      <template #footer>
        <button type="button" class="btn btn--primary" @click="issuedSecret = null">{{ t('account.tokens.done') }}</button>
      </template>
    </ModalDialog>

    <ConfirmDialog
      :open="pendingRevoke !== null"
      :title="t('account.tokens.revoke')"
      :message="t('account.tokens.confirmRevoke', { name: pendingRevoke?.name ?? '' })"
      :confirm-label="t('account.tokens.revoke')"
      danger
      :busy="revoking"
      @confirm="confirmRevoke"
      @cancel="pendingRevoke = null"
    />
  </div>
</template>
