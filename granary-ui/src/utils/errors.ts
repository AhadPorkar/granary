import { isApiError } from '@/api/client';

export type Translate = (key: string, values?: Record<string, unknown>) => string;
export type TranslationExists = (key: string) => boolean;

export interface Translator {
  t: Translate;
  te: TranslationExists;
}

/**
 * The user-facing text for any failure. API errors are looked up as {@code errors.<code>} in the current
 * locale (with English as fallback); unknown codes fall back to the server's English message.
 */
export function translateError(error: unknown, i18n: Translator): string {
  if (isApiError(error)) {
    const key = `errors.${error.code}`;
    if (i18n.te(key)) {
      const translated = i18n.t(key, error.details);
      if (translated && translated !== key) {
        return translated;
      }
    }
    if (error.message) {
      return error.message;
    }
    return i18n.t('errors.unknown');
  }
  if (error instanceof Error && error.message) {
    return error.message;
  }
  return i18n.t('errors.unknown');
}

/** Error codes that belong to one form field, so the message can be shown next to it. */
const FIELD_BY_CODE: Record<string, string> = {
  'repository.name.invalid': 'name',
  'repository.name.taken': 'name',
  'repository.format.unknown': 'format',
  'repository.format.immutable': 'format',
  'repository.type.missing': 'type',
  'repository.type.immutable': 'type',
  'repository.blobstore.unknown': 'blobStore',
  'repository.remoteUrl.missing': 'remoteUrl',
  'repository.remoteUrl.invalid': 'remoteUrl',
  'repository.members.notAllowed': 'members',
  'repository.members.empty': 'members',
  'repository.members.duplicate': 'members',
  'repository.members.self': 'members',
  'repository.members.unknown': 'members',
  'repository.members.formatMismatch': 'members',
  'repository.members.cycle': 'members',
  'user.username.invalid': 'username',
  'user.username.reserved': 'username',
  'user.username.taken': 'username',
  'user.password.tooShort': 'password',
  'auth.password.wrong': 'currentPassword',
  'role.name.invalid': 'name',
  'role.name.taken': 'name',
  'role.privilege.invalid': 'privileges',
  'token.name.invalid': 'name',
  'token.expiry.past': 'expiresAt',
};

/**
 * Maps an API error onto form fields: {@code {field: message}}. Bean-validation failures ({@code validation})
 * carry one entry per field in {@code details}; domain errors are mapped by code, and the generic
 * {@code repository.field.*} codes name their field in {@code details.field}.
 */
export function fieldErrors(error: unknown, i18n: Translator): Record<string, string> {
  if (!isApiError(error)) {
    return {};
  }
  const result: Record<string, string> = {};
  if (error.code === 'validation') {
    for (const [field, message] of Object.entries(error.details)) {
      result[field] = typeof message === 'string' ? message : translateError(error, i18n);
    }
    return result;
  }
  const detailField = typeof error.details.field === 'string' ? error.details.field : undefined;
  const field = FIELD_BY_CODE[error.code] ?? detailField;
  if (field) {
    result[field] = translateError(error, i18n);
  }
  return result;
}
