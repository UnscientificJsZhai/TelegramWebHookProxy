import {describe, expect, it} from 'vitest';
import {resolveModelSelection} from './modelSelection';

describe('model selection hydration', () => {
    it('shows the running model when no model has been explicitly saved', () => {
        expect(resolveModelSelection(null, '', 'runtime-model')).toEqual({value: 'runtime-model', dirty: false});
    });

    it('keeps the saved selection while the model endpoint is loading', () => {
        expect(resolveModelSelection(null, 'saved-model', null)).toEqual({value: 'saved-model', dirty: false});
    });

    it('honors an explicitly cleared server selection instead of restoring stale saved settings', () => {
        expect(resolveModelSelection(null, 'old-model', '')).toEqual({value: '', dirty: false});
    });

    it('preserves a user selection when a model list response arrives', () => {
        expect(resolveModelSelection('draft-model', '', 'runtime-model')).toEqual({value: 'draft-model', dirty: true});
    });

    it('does not mark returning to the current model as unsaved', () => {
        expect(resolveModelSelection('runtime-model', '', 'runtime-model')).toEqual({
            value: 'runtime-model',
            dirty: false
        });
    });

    it('reflects a refreshed server choice and allows selecting the old choice', () => {
        expect(resolveModelSelection(null, 'old-model', 'new-model')).toEqual({value: 'new-model', dirty: false});
        expect(resolveModelSelection('old-model', 'old-model', 'new-model')).toEqual({value: 'old-model', dirty: true});
    });
});
