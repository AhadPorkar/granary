<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import type { FormatView, RepositoryRequest, RepositoryType, RepositoryView, VersionPolicy } from '@/api/types';
import { useApiError } from '@/composables/useApiError';
import FormField from './FormField.vue';
import ToggleSwitch from './ToggleSwitch.vue';
import MemberPicker from './MemberPicker.vue';
import ErrorAlert from './ErrorAlert.vue';

const props = withDefaults(
  defineProps<{
    mode: 'create' | 'edit';
    initial?: RepositoryView | null;
    formats: FormatView[];
    repositories: RepositoryView[];
    error?: unknown;
    saving?: boolean;
  }>(),
  { initial: null, error: undefined, saving: false },
);

const emit = defineEmits<{ submit: [request: RepositoryRequest]; cancel: [] }>();

const { t } = useI18n();
const apiError = useApiError();

const TYPES: RepositoryType[] = ['HOSTED', 'PROXY', 'GROUP'];
const POLICIES: VersionPolicy[] = ['MIXED', 'RELEASE', 'SNAPSHOT'];
const NAME_PATTERN = /^[a-zA-Z0-9][a-zA-Z0-9._-]{0,99}$/;
const MAVEN_FORMAT = 'maven2';

const form = reactive({
  name: '',
  format: '',
  type: 'HOSTED' as RepositoryType,
  online: true,
  description: '',
  allowRedeploy: false,
  versionPolicy: 'MIXED' as VersionPolicy,
  remoteUrl: '',
  remoteUsername: '',
  remotePassword: '',
  remoteToken: '',
  connectTimeoutSeconds: '' as string | number,
  readTimeoutSeconds: '' as string | number,
  metadataMaxAgeMinutes: '' as string | number,
  negativeCacheMinutes: '' as string | number,
  offlineMode: false,
  members: [] as string[],
});

const localErrors = ref<Record<string, string>>({});

watch(
  () => props.initial,
  (initial) => {
    if (initial) {
      form.name = initial.name;
      form.format = initial.format;
      form.type = initial.type;
      form.online = initial.online;
      form.description = initial.description ?? '';
      form.allowRedeploy = initial.allowRedeploy;
      form.versionPolicy = initial.versionPolicy ?? 'MIXED';
      form.remoteUrl = initial.remoteUrl ?? '';
      form.remoteUsername = initial.remoteUsername ?? '';
      form.remotePassword = '';
      form.remoteToken = '';
      form.connectTimeoutSeconds = numberToInput(initial.connectTimeoutSeconds);
      form.readTimeoutSeconds = numberToInput(initial.readTimeoutSeconds);
      form.metadataMaxAgeMinutes = numberToInput(initial.metadataMaxAgeMinutes);
      form.negativeCacheMinutes = numberToInput(initial.negativeCacheMinutes);
      form.offlineMode = initial.offlineMode;
      form.members = [...initial.members];
    }
  },
  { immediate: true },
);

watch(
  () => props.formats,
  (formats) => {
    if (!form.format && formats.length) {
      form.format = formats[0].id;
    }
  },
  { immediate: true },
);

const isEdit = computed(() => props.mode === 'edit');
const isHosted = computed(() => form.type === 'HOSTED');
const isProxy = computed(() => form.type === 'PROXY');
const isGroup = computed(() => form.type === 'GROUP');
const isMaven = computed(() => form.format === MAVEN_FORMAT);

const memberCandidates = computed(() =>
  props.repositories.filter((r) => r.format === form.format && r.name !== form.name).map((r) => r.name),
);

const errors = computed<Record<string, string>>(() => ({ ...apiError.fields(props.error), ...localErrors.value }));

/** The general error banner only shows errors that no field claims. */
const generalError = computed(() => (props.error && Object.keys(apiError.fields(props.error)).length === 0 ? props.error : null));

function numberToInput(value: number | null | undefined): string {
  return value === null || value === undefined ? '' : String(value);
}

/** v-model casts type="number" inputs to numbers but leaves a cleared field as ''. */
function inputToNumber(value: string | number): number | null {
  const trimmed = String(value).trim();
  if (trimmed === '') {
    return null;
  }
  const n = Number(trimmed);
  return Number.isFinite(n) ? n : null;
}

function validate(): boolean {
  const found: Record<string, string> = {};
  if (!form.name.trim()) {
    found.name = t('validation.required');
  } else if (!NAME_PATTERN.test(form.name.trim())) {
    found.name = t('errors.repository.name.invalid');
  }
  if (!form.format) {
    found.format = t('validation.required');
  }
  if (isProxy.value) {
    const url = form.remoteUrl.trim();
    if (!url) {
      found.remoteUrl = t('validation.required');
    } else if (!/^https?:\/\/[^\s/]+/i.test(url)) {
      found.remoteUrl = t('validation.invalidUrl');
    }
    for (const field of ['connectTimeoutSeconds', 'readTimeoutSeconds'] as const) {
      const n = inputToNumber(form[field]);
      if (String(form[field]).trim() !== '' && (n === null || n <= 0)) {
        found[field] = t('validation.positive');
      }
    }
    for (const field of ['metadataMaxAgeMinutes', 'negativeCacheMinutes'] as const) {
      const n = inputToNumber(form[field]);
      if (String(form[field]).trim() !== '' && (n === null || n < 0)) {
        found[field] = t('validation.nonNegative');
      }
    }
  }
  if (isGroup.value && form.members.length === 0) {
    found.members = t('errors.repository.members.empty');
  }
  localErrors.value = found;
  return Object.keys(found).length === 0;
}

function buildRequest(): RepositoryRequest {
  const request: RepositoryRequest = {
    name: form.name.trim(),
    format: form.format,
    type: form.type,
    online: form.online,
    description: form.description.trim() || null,
    allowRedeploy: isHosted.value ? form.allowRedeploy : false,
    versionPolicy: isHosted.value && isMaven.value ? form.versionPolicy : 'MIXED',
    members: isGroup.value ? [...form.members] : [],
    offlineMode: isProxy.value ? form.offlineMode : false,
  };
  if (isProxy.value) {
    request.remoteUrl = form.remoteUrl.trim();
    request.remoteUsername = form.remoteUsername.trim() || null;
    // Blank secrets mean "keep what is stored" on update; on create they are simply absent.
    request.remotePassword = form.remotePassword || null;
    request.remoteToken = form.remoteToken || null;
    request.connectTimeoutSeconds = inputToNumber(form.connectTimeoutSeconds);
    request.readTimeoutSeconds = inputToNumber(form.readTimeoutSeconds);
    request.metadataMaxAgeMinutes = inputToNumber(form.metadataMaxAgeMinutes);
    request.negativeCacheMinutes = inputToNumber(form.negativeCacheMinutes);
  }
  return request;
}

function submit(): void {
  if (!validate()) {
    return;
  }
  emit('submit', buildRequest());
}

defineExpose({ form });
</script>

<template>
  <form class="stack" novalidate data-testid="repository-form" @submit.prevent="submit">
    <ErrorAlert :error="generalError" />

    <div class="form-grid">
      <FormField id="repo-name" :label="t('repositories.fields.name')" :hint="isEdit ? undefined : t('repositories.hints.name')" :error="errors.name" required>
        <template #default="{ id, invalid, describedBy }">
          <input :id="id" v-model="form.name" class="input" type="text" autocomplete="off" :readonly="isEdit" :aria-invalid="invalid" :aria-describedby="describedBy" required maxlength="100" />
        </template>
      </FormField>

      <FormField id="repo-format" :label="t('repositories.fields.format')" :error="errors.format" required>
        <template #default="{ id, invalid, describedBy }">
          <select :id="id" v-model="form.format" class="select" :disabled="isEdit" :aria-invalid="invalid" :aria-describedby="describedBy" data-testid="format-select">
            <option v-for="f in formats" :key="f.id" :value="f.id">{{ f.displayName }}</option>
          </select>
        </template>
      </FormField>
    </div>

    <fieldset class="field" style="border: 0; padding: 0; margin: 0">
      <legend class="field__label">{{ t('repositories.fields.type') }}</legend>
      <div class="type-options">
        <label v-for="type in TYPES" :key="type" class="radio type-option" :class="{ 'type-option--selected': form.type === type }">
          <input v-model="form.type" type="radio" name="repo-type" :value="type" :disabled="isEdit" :data-testid="`type-${type}`" />
          <span class="checkbox__text">
            <strong>{{ t(`repositories.types.${type}`) }}</strong>
            <span class="field__hint">{{ t(`repositories.typeHints.${type}`) }}</span>
          </span>
        </label>
      </div>
    </fieldset>

    <FormField id="repo-description" :label="t('repositories.fields.description')" :error="errors.description" optional>
      <template #default="{ id, invalid, describedBy }">
        <input :id="id" v-model="form.description" class="input" type="text" maxlength="500" :aria-invalid="invalid" :aria-describedby="describedBy" />
      </template>
    </FormField>

    <ToggleSwitch id="repo-online" v-model="form.online" :label="t('repositories.fields.online')" :hint="t('repositories.hints.online')" />

    <section v-if="isHosted" class="form-section" data-testid="hosted-section">
      <h2 class="form-section__title">{{ t('repositories.sections.hosted') }}</h2>
      <FormField v-if="isMaven" id="repo-version-policy" :label="t('repositories.fields.versionPolicy')" :hint="t('repositories.hints.versionPolicy')" :error="errors.versionPolicy">
        <template #default="{ id, invalid, describedBy }">
          <select :id="id" v-model="form.versionPolicy" class="select" :aria-invalid="invalid" :aria-describedby="describedBy" data-testid="version-policy">
            <option v-for="policy in POLICIES" :key="policy" :value="policy">{{ t(`repositories.versionPolicies.${policy}`) }}</option>
          </select>
        </template>
      </FormField>
      <ToggleSwitch id="repo-allow-redeploy" v-model="form.allowRedeploy" :label="t('repositories.fields.allowRedeploy')" :hint="t('repositories.hints.allowRedeploy')" />
    </section>

    <section v-if="isProxy" class="form-section" data-testid="proxy-section">
      <h2 class="form-section__title">{{ t('repositories.sections.proxy') }}</h2>
      <FormField id="repo-remote-url" :label="t('repositories.fields.remoteUrl')" :hint="t('repositories.hints.remoteUrl')" :error="errors.remoteUrl" required>
        <template #default="{ id, invalid, describedBy }">
          <input :id="id" v-model="form.remoteUrl" class="input input--mono ltr" type="url" inputmode="url" placeholder="https://" :aria-invalid="invalid" :aria-describedby="describedBy" required />
        </template>
      </FormField>

      <div class="form-grid">
        <FormField id="repo-connect-timeout" :label="t('repositories.fields.connectTimeout')" :hint="t('repositories.hints.timeouts')" :error="errors.connectTimeoutSeconds" optional>
          <template #default="{ id, invalid, describedBy }">
            <input :id="id" v-model="form.connectTimeoutSeconds" class="input" type="number" min="1" step="1" :aria-invalid="invalid" :aria-describedby="describedBy" />
          </template>
        </FormField>
        <FormField id="repo-read-timeout" :label="t('repositories.fields.readTimeout')" :hint="t('repositories.hints.timeouts')" :error="errors.readTimeoutSeconds" optional>
          <template #default="{ id, invalid, describedBy }">
            <input :id="id" v-model="form.readTimeoutSeconds" class="input" type="number" min="1" step="1" :aria-invalid="invalid" :aria-describedby="describedBy" />
          </template>
        </FormField>
        <FormField id="repo-metadata-max-age" :label="t('repositories.fields.metadataMaxAge')" :hint="t('repositories.hints.metadataMaxAge')" :error="errors.metadataMaxAgeMinutes" optional>
          <template #default="{ id, invalid, describedBy }">
            <input :id="id" v-model="form.metadataMaxAgeMinutes" class="input" type="number" min="0" step="1" :aria-invalid="invalid" :aria-describedby="describedBy" />
          </template>
        </FormField>
        <FormField id="repo-negative-cache" :label="t('repositories.fields.negativeCache')" :hint="t('repositories.hints.negativeCache')" :error="errors.negativeCacheMinutes" optional>
          <template #default="{ id, invalid, describedBy }">
            <input :id="id" v-model="form.negativeCacheMinutes" class="input" type="number" min="0" step="1" :aria-invalid="invalid" :aria-describedby="describedBy" />
          </template>
        </FormField>
      </div>

      <ToggleSwitch id="repo-offline-mode" v-model="form.offlineMode" :label="t('repositories.fields.offlineMode')" :hint="t('repositories.hints.offlineMode')" />

      <h2 class="form-section__title">{{ t('repositories.sections.remoteAuth') }}</h2>
      <p class="field__hint" style="margin: 0">{{ t('repositories.hints.remoteAuth') }}</p>
      <div class="form-grid">
        <FormField id="repo-remote-username" :label="t('repositories.fields.remoteUsername')" :error="errors.remoteUsername" optional>
          <template #default="{ id, invalid, describedBy }">
            <input :id="id" v-model="form.remoteUsername" class="input" type="text" autocomplete="off" :aria-invalid="invalid" :aria-describedby="describedBy" />
          </template>
        </FormField>
        <FormField id="repo-remote-password" :label="t('repositories.fields.remotePassword')" :error="errors.remotePassword" optional>
          <template #default="{ id, invalid, describedBy }">
            <input
              :id="id"
              v-model="form.remotePassword"
              class="input"
              type="password"
              autocomplete="new-password"
              :placeholder="isEdit && initial?.hasRemotePassword ? `(${t('repositories.keepSecret')})` : undefined"
              :aria-invalid="invalid"
              :aria-describedby="describedBy"
            />
          </template>
        </FormField>
        <FormField id="repo-remote-token" :label="t('repositories.fields.remoteToken')" :hint="t('repositories.hints.remoteToken')" :error="errors.remoteToken" optional>
          <template #default="{ id, invalid, describedBy }">
            <input
              :id="id"
              v-model="form.remoteToken"
              class="input"
              type="password"
              autocomplete="off"
              :placeholder="isEdit && initial?.hasRemoteToken ? `(${t('repositories.keepSecret')})` : undefined"
              :aria-invalid="invalid"
              :aria-describedby="describedBy"
            />
          </template>
        </FormField>
      </div>
    </section>

    <section v-if="isGroup" class="form-section" data-testid="group-section">
      <h2 class="form-section__title">{{ t('repositories.sections.group') }}</h2>
      <FormField id="repo-members" :label="t('repositories.fields.members')" :hint="t('repositories.hints.members')" :error="errors.members" required>
        <template #default="{ id }">
          <MemberPicker :id="id" v-model="form.members" :candidates="memberCandidates" :format="form.format" />
        </template>
      </FormField>
    </section>

    <div class="form-actions">
      <button type="button" class="btn" :disabled="saving" @click="emit('cancel')">{{ t('common.cancel') }}</button>
      <button type="submit" class="btn btn--primary" :disabled="saving" data-testid="submit">
        <span v-if="saving" class="spinner" aria-hidden="true"></span>
        {{ isEdit ? t('common.save') : t('repositories.create') }}
      </button>
    </div>
  </form>
</template>

<style scoped>
.type-options {
  display: grid;
  gap: 0.5rem;
  grid-template-columns: repeat(auto-fit, minmax(min(100%, 14rem), 1fr));
}

.type-option {
  padding: 0.65rem 0.75rem;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: var(--surface);
}

.type-option--selected {
  border-color: var(--primary);
  background: var(--primary-soft);
}

.type-option input:disabled + .checkbox__text {
  opacity: 0.7;
}
</style>
