import { describe, expect, it } from 'vitest';
import { ApiError } from '@/api/client';
import { i18n } from '@/i18n';
import { fieldErrors, translateError } from '@/utils/errors';

function translator() {
  return {
    t: (key: string, values?: Record<string, unknown>) => (values ? i18n.global.t(key, values) : i18n.global.t(key)),
    te: (key: string) => i18n.global.te(key),
  };
}

function apiError(code: string, message = 'server message', details: Record<string, unknown> = {}, status = 400): ApiError {
  return new ApiError({ code, message, details, status });
}

describe('translateError', () => {
  it('translates a known code in the current locale', () => {
    expect(translateError(apiError('auth.invalid'), translator())).toBe('Invalid username or password.');
    i18n.global.locale.value = 'de';
    expect(translateError(apiError('auth.invalid'), translator())).toBe('Ungültiger Benutzername oder ungültiges Passwort.');
  });

  it('falls back to the server message for an unknown code', () => {
    expect(translateError(apiError('something.new', 'Server says hi'), translator())).toBe('Server says hi');
  });

  it('interpolates the error details into the translation', () => {
    const error = apiError('repository.members.unknown', 'x', { member: 'npm-proxy' });
    expect(translateError(error, translator())).toBe('The member repository “npm-proxy” does not exist.');
  });

  it('handles plain errors and unknown values', () => {
    expect(translateError(new Error('boom'), translator())).toBe('boom');
    expect(translateError(undefined, translator())).toBe('An unexpected error occurred.');
  });
});

describe('fieldErrors', () => {
  it('maps bean-validation failures onto their fields', () => {
    const error = apiError('validation', 'Request validation failed', { name: 'must not be blank', format: 'must not be blank' });
    expect(fieldErrors(error, translator())).toEqual({ name: 'must not be blank', format: 'must not be blank' });
  });

  it('maps domain codes onto the field they belong to', () => {
    expect(fieldErrors(apiError('repository.name.taken'), translator())).toEqual({ name: 'A repository with this name already exists.' });
    expect(fieldErrors(apiError('user.password.tooShort'), translator())).toHaveProperty('password');
    expect(fieldErrors(apiError('repository.remoteUrl.invalid'), translator())).toHaveProperty('remoteUrl');
  });

  it('uses details.field for the generic numeric-field codes and nothing for unrelated codes', () => {
    const positive = apiError('repository.field.positive', 'x', { field: 'readTimeoutSeconds' });
    expect(fieldErrors(positive, translator())).toEqual({ readTimeoutSeconds: 'readTimeoutSeconds must be greater than zero.' });
    expect(fieldErrors(apiError('internal', 'x', {}, 500), translator())).toEqual({});
    expect(fieldErrors(new Error('x'), translator())).toEqual({});
  });
});
