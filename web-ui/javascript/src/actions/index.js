/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

import React from 'react';
import { toast } from 'react-toastify';
import {Mutex, withTimeout} from 'async-mutex';

import IssueReportToast from "../components/IssueReportToast";
import PackDiagramModel from "../components/diagram/models/PackDiagramModel";
import {fetchDeviceInfos, fetchDevicePacks, addFromLibrary, removeFromDevice, reorderPacks, addToLibrary} from '../services/device';
import {SUBSCRIBED} from '../services/eventBusChannel';
import {fetchLibraryInfos, fetchLibraryPacks, downloadFromLibrary, uploadToLibrary, convertInLibrary, removeFromLibrary} from '../services/library';
import {fetchEvergreenInfos, fetchEvergreenLatestRelease, fetchEvergreenAnnounce} from '../services/evergreen';
import {generateFilename, sortPacks} from "../utils/packs";
import {readFromArchive} from "../utils/reader";
import {simplifiedSample} from "../utils/sample";


const mutex = withTimeout(new Mutex(), 100);

export const actionLoadLibrary = (t) => {
    return dispatch => {
        let toastId = toast(t('toasts.library.loading'), { autoClose: false });
        return fetchLibraryInfos()
            .then(metadata => {
                console.log("fetching library packs...");
                toast.update(toastId,{ render: t('toasts.library.fetching') });
                return fetchLibraryPacks()
                    .then(packs => {
                        toast.update(toastId, { type: toast.TYPE.INFO, render: t('toasts.library.fetched', { count: packs.length }), autoClose: 5000 });
                        dispatch(setLibrary(metadata, packs));
                    })
                    .catch(e => {
                        console.error('failed to fetch library packs', e);
                        toast.update(toastId, { type: toast.TYPE.ERROR, render: <IssueReportToast content={<>{t('toasts.library.fetchingFailed')}</>} error={e} />, autoClose: false });
                    });
            })
            .catch(e => {
                console.error('failed to fetch library infos', e);
                toast.update(toastId, { type: toast.TYPE.ERROR, render: <IssueReportToast content={<>{t('toasts.library.loadingFailed')}</>} error={e} />, autoClose: false });
            });
    }
};

export const setLibrary = (metadata, packs) => ({
    type: 'SET_LIBRARY',
    metadata,
    packs: sortPacks(packs)
});


export const actionCheckDevice = (t) => {
    return dispatch => mutex.acquire()
        .then(
            release => {
                let toastId = toast(t('toasts.device.checking'), { autoClose: false });
                return fetchDeviceInfos()
                    .then(metadata => {
                        if (metadata && Object.keys(metadata).length > 0 && metadata.plugged) {
                            toast.update(toastId, { type: toast.TYPE.INFO, render: t('toasts.device.plugged'), autoClose: 5000 });
                            dispatch(actionDevicePlugged(metadata, t));
                        } else {
                            // Device not plugged, nothing to do
                            toast.dismiss(toastId);
                        }
                    })
                    .catch(e => {
                        console.error('failed to fetch device infos', e);
                        toast.update(toastId, { type: toast.TYPE.ERROR, render: <IssueReportToast content={<>{t('toasts.device.checkingFailed')}</>} error={e} />, autoClose: false });
                    })
                    .finally(() => {
                        // Always release the mutex
                        release();
                    });
            },
            e => {
                // Device is busy
                toast.error(t('toasts.device.busy'));
            });
};

export const actionDevicePlugged = (metadata, t) => {
    return dispatch => mutex.acquire()
        .then(
            release => {
            dispatch(devicePlugged(metadata));

            console.log("fetching device packs...");
            let toastId = toast(t('toasts.device.fetching'), { autoClose: false });
            return fetchDevicePacks()
                .then(packs => {
                    toast.update(toastId, { type: toast.TYPE.INFO, render: t('toasts.device.fetched', { count: packs.length }), autoClose: 5000 });
                    dispatch(setDevicePacks(packs));
                })
                .catch(e => {
                    console.error('failed to fetch device packs', e);
                    toast.update(toastId, { type: toast.TYPE.ERROR, render: <IssueReportToast content={<>{t('toasts.device.fetchingFailed')}</>} error={e} />, autoClose: false });
                })
                .finally(() => {
                    // Always release the mutex
                    release();
                });
            },
            e => {
                // Device is busy
                toast.error(t('toasts.device.busy'));
            });
};

export const actionRefreshDevice = (t) => {
    return dispatch => mutex.acquire()
        .then(
            release => {
                return fetchDeviceInfos()
                    .then(metadata => {
                        if (metadata && Object.keys(metadata).length > 0 && metadata.plugged) {
                            return fetchDevicePacks()
                                .then(packs => {
                                    dispatch(devicePlugged(metadata));
                                    dispatch(setDevicePacks(packs));
                                });
                        }
                    })
                    .catch(e => {
                        console.error('failed to refresh device', e);
                        toast(<IssueReportToast content={<>{t('toasts.device.refreshingFailed')}</>} error={e} />, { type: toast.TYPE.ERROR, autoClose: false });
                    })
                    .finally(() => {
                        // Always release the mutex
                        release();
                    });
            },
            e => {
                // Device is busy
                toast.error(t('toasts.device.busy'));
            });
};

export const actionAddFromLibrary = (uuid, path, format, driver, context, t) => {
    return dispatch => mutex.acquire()
        .then(
            release => {
                // First, make sure the story pack is in the right format.
                if (driver !== format) {
                    console.error('pack format is not compatible with the device');
                    toast.error(t('toasts.device.notCompatible'));
                    // Always release the mutex
                    release();
                } else {
                    // Then start transfer
                    let toastId = toast(t('toasts.device.adding'), { autoClose: false });
                    return addFromLibrary(uuid, path)
                        .then(resp => {
                            // From here on the backend has accepted the transfer and is running it.
                            // Nothing that happens to the monitoring channel below changes that, so
                            // no failure reported from this point may claim the transfer failed.
                            const transferId = resp.transferId;
                            const progressAddress = 'storyteller.transfer.' + transferId + '.progress';
                            const doneAddress = 'storyteller.transfer.' + transferId + '.done';
                            console.log('transfer ' + transferId + ': accepted by the backend (add to device)');

                            let released = false;
                            const releaseOnce = () => {
                                if (!released) {
                                    released = true;
                                    release();
                                }
                            };

                            const onProgress = (error, message) => {
                                if (message.body.progress < 1) {
                                    toast.update(toastId, {progress: message.body.progress, autoClose: false});
                                }
                            };
                            const onDone = (error, message) => {
                                console.log('transfer ' + transferId + ': backend reported ' + (message.body.success ? 'success' : 'failure'));
                                // Release the subscriptions so repeated transfers cannot pile up.
                                context.channel.unsubscribe(progressAddress, onProgress);
                                context.channel.unsubscribe(doneAddress, onDone);
                                if (message.body.success) {
                                    toast.update(toastId, {progress: null, type: toast.TYPE.SUCCESS, render: t('toasts.device.added'), autoClose: 5000});
                                    // Refresh device metadata and packs list
                                    dispatch(actionRefreshDevice(t));
                                } else {
                                    toast.update(toastId, {progress: null, type: toast.TYPE.ERROR, render: <IssueReportToast content={<>{t('toasts.device.addingFailed')}</>} />, autoClose: false });
                                }
                                releaseOnce();
                            };

                            context.channel.subscribe(progressAddress, onProgress);
                            const doneSubscription = context.channel.subscribe(doneAddress, onDone);

                            if (doneSubscription !== SUBSCRIBED) {
                                // The channel is down, so the outcome cannot be observed. The transfer
                                // is still running on the backend: say that, and do not claim a failure.
                                // The subscription stays recorded, so if the channel comes back before
                                // the backend publishes its verdict, onDone still resolves this toast.
                                console.warn('transfer ' + transferId + ': monitoring channel unavailable, transfer continues on the backend');
                                toast.update(toastId, {progress: null, type: toast.TYPE.WARNING, render: t('toasts.device.trackingLost'), autoClose: false});
                                releaseOnce();
                            }
                        })
                        .catch(e => {
                            // Only a real request failure reaches this point: subscribing can no
                            // longer throw, so a dropped channel never lands here.
                            console.error('failed to add pack to device', e);
                            // showDetails, here and nowhere else for now: an upload can be refused for
                            // a precise reason the driver spells out — a destination folder that was
                            // already there, and that STUdio deliberately will not touch. That text
                            // already arrived in the Error and used to go no further than a bug report,
                            // leaving the user with "adding failed" and no idea why.
                            toast.update(toastId, { type: toast.TYPE.ERROR, render: <IssueReportToast content={<>{t('toasts.device.addingFailed')}</>} error={e} showDetails />, autoClose: false });
                            // Always release the mutex
                            release();
                        });
                }
            },
            e => {
                // Device is busy
                toast.error(t('toasts.device.busy'));
            });
};

export const actionRemoveFromDevice = (uuid, t) => {
    return dispatch => mutex.acquire()
        .then(
            release => {
                let toastId = toast(t('toasts.device.removing'), { autoClose: false });
                return removeFromDevice(uuid)
                    .then(resp => {
                        if (resp.success) {
                            toast.update(toastId, {type: toast.TYPE.SUCCESS, render: t('toasts.device.removed'), autoClose: 5000});
                            // Refresh device metadata and packs list
                            dispatch(actionRefreshDevice(t));
                        } else {
                            toast.update(toastId, { type: toast.TYPE.ERROR, render: <IssueReportToast content={<>{t('toasts.device.removingFailed')}</>} />, autoClose: false });
                        }
                    })
                    .catch(e => {
                        console.error('failed to remove pack from device', e);
                        toast.update(toastId, { type: toast.TYPE.ERROR, render: <IssueReportToast content={<>{t('toasts.device.removingFailed')}</>} error={e} />, autoClose: false });
                    })
                    .finally(() => {
                        // Always release the mutex
                        release();
                    });
            },
            e => {
                // Device is busy
                toast.error(t('toasts.device.busy'));
            });
};

export const actionReorderOnDevice = (uuids, t) => {
    return dispatch => mutex.acquire()
        .then(
            release => {
                let toastId = toast(t('toasts.device.reordering'), { autoClose: false });
                return reorderPacks(uuids)
                    .then(resp => {
                        if (resp.success) {
                            toast.update(toastId, {type: toast.TYPE.SUCCESS, render: t('toasts.device.reordered'), autoClose: 5000});
                            // Refresh device metadata and packs list
                            dispatch(actionRefreshDevice(t));
                        } else {
                            toast.update(toastId, { type: toast.TYPE.ERROR, render: <IssueReportToast content={<>{t('toasts.device.reorderingFailed')}</>} />, autoClose: false });
                        }
                    })
                    .catch(e => {
                        console.error('failed to reorder packs on device', e);
                        toast.update(toastId, { type: toast.TYPE.ERROR, render: <IssueReportToast content={<>{t('toasts.device.reorderingFailed')}</>} error={e} />, autoClose: false });
                    })
                    .finally(() => {
                        // Always release the mutex
                        release();
                    });
            },
            e => {
                // Device is busy
                toast.error(t('toasts.device.busy'));
            });
};

export const actionAddToLibrary = (uuid, driver, context, t) => {
    return dispatch => mutex.acquire()
        .then(
            release => {
                let toastId = toast(t('toasts.library.adding'), { autoClose: false });
                return addToLibrary(uuid, driver)
                    .then(resp => {
                        // Same contract as actionAddFromLibrary: past this point the extraction is
                        // running on the backend and the channel's health says nothing about it.
                        const transferId = resp.transferId;
                        const progressAddress = 'storyteller.transfer.' + transferId + '.progress';
                        const doneAddress = 'storyteller.transfer.' + transferId + '.done';
                        console.log('transfer ' + transferId + ': accepted by the backend (add to library)');

                        let released = false;
                        const releaseOnce = () => {
                            if (!released) {
                                released = true;
                                release();
                            }
                        };

                        const onProgress = (error, message) => {
                            if (message.body.progress < 1) {
                                toast.update(toastId, {progress: message.body.progress, autoClose: false});
                            }
                        };
                        const onDone = (error, message) => {
                            console.log('transfer ' + transferId + ': backend reported ' + (message.body.success ? 'success' : 'failure'));
                            context.channel.unsubscribe(progressAddress, onProgress);
                            context.channel.unsubscribe(doneAddress, onDone);
                            if (message.body.success) {
                                toast.update(toastId, {progress: null, type: toast.TYPE.SUCCESS, render: t('toasts.library.added'), autoClose: 5000});
                                // Refresh device metadata and packs list
                                dispatch(actionRefreshLibrary(t));
                            } else {
                                toast.update(toastId, {progress: null, type: toast.TYPE.ERROR, render: <IssueReportToast content={<>{t('toasts.library.addingFailed')}</>} />, autoClose: false });
                            }
                            releaseOnce();
                        };

                        context.channel.subscribe(progressAddress, onProgress);
                        const doneSubscription = context.channel.subscribe(doneAddress, onDone);

                        if (doneSubscription !== SUBSCRIBED) {
                            console.warn('transfer ' + transferId + ': monitoring channel unavailable, extraction continues on the backend');
                            toast.update(toastId, {progress: null, type: toast.TYPE.WARNING, render: t('toasts.library.trackingLost'), autoClose: false});
                            releaseOnce();
                        }
                    })
                    .catch(e => {
                        console.error('failed to add pack to library', e);
                        toast.update(toastId, { type: toast.TYPE.ERROR, render: <IssueReportToast content={<>{t('toasts.library.addingFailed')}</>} error={e} />, autoClose: false });
                        // Always release the mutex
                        release();
                    });
            },
            e => {
                // Device is busy
                toast.error(t('toasts.device.busy'));
            });
};

export const actionRefreshLibrary = (t) => {
    return dispatch => {
        return fetchLibraryInfos()
            .then(metadata => {
                return fetchLibraryPacks()
                    .then(packs => {
                        dispatch(setLibrary(metadata, packs));
                    });
            })
            .catch(e => {
                console.error('failed to refresh library', e);
                toast(<IssueReportToast content={<>{t('toasts.library.refreshingFailed')}</>} error={e} />, { type: toast.TYPE.ERROR, autoClose: false });
            });
    }
};

export const actionDownloadFromLibrary = (uuid, path, t) => {
    return dispatch => {
        let toastId = toast(t('toasts.library.downloading'), { autoClose: false });
        return downloadFromLibrary(uuid, path)
            .then(async resp => {
                const reader = resp.body.getReader();
                const contentLength = +resp.headers.get('Content-Length');
                // Read chunks and monitor progress
                let receivedLength = 0;
                let chunks = [];
                while (true) {
                    const {done, value} = await reader.read();
                    if (done) {
                        break;
                    }
                    chunks.push(value);
                    receivedLength += value.length;
                    let progress = (receivedLength / contentLength);
                    console.log(`Received ${receivedLength} of ${contentLength}: ${progress}`);
                    toast.update(toastId, {progress: progress, autoClose: false});
                }
                let blob = new Blob(chunks);
                toast.update(toastId, {
                    progress: null,
                    type: toast.TYPE.SUCCESS,
                    render: t('toasts.library.downloaded'),
                    autoClose: 5000
                });
                return blob;
            })
            .catch(e => {
                console.error('failed to download pack from library', e);
                toast.update(toastId, { type: toast.TYPE.ERROR, render: <IssueReportToast content={<>{t('toasts.library.downloadingFailed')}</>} error={e} />, autoClose: false });
            });
    }
};

export const actionLoadPackInEditor = (packData, filename, t) => {
    return dispatch => {
        let toastId = toast(t('toasts.editor.loading'), { autoClose: false });
        readFromArchive(packData)
            .then(loadedModel => {
                toast.update(toastId, { type: toast.TYPE.SUCCESS, render: t('toasts.editor.loaded'), autoClose: 5000});
                // Set loaded model in editor
                dispatch(setEditorDiagram(loadedModel, filename));
                // Show editor
                dispatch(showEditor());
            })
            .catch(e => {
                console.error('failed to load story pack', e);
                toast.update(toastId, { type: toast.TYPE.ERROR, render: <IssueReportToast content={<>{t('toasts.editor.loadingFailed')}</>} error={e} />, autoClose: false });
            });
    }
};

export const actionCreatePackInEditor = (t) => {
    return dispatch => {
        // Set empty model in editor
        let model = new PackDiagramModel();
        dispatch(setEditorDiagram(model));
        // Show editor
        dispatch(showEditor());
    }
};

export const actionLoadSampleInEditor = (t) => {
    return dispatch => {
        // Set sample model in editor
        let model = simplifiedSample();
        dispatch(setEditorDiagram(model, generateFilename(model)));
        // Show editor
        dispatch(showEditor());
    }
};

export const actionUploadToLibrary = (uuid, path, packData, t) => {
    return dispatch => {
        let toastId = toast(t('toasts.library.uploading'), { autoClose: false });
        return uploadToLibrary(uuid, path, packData,
            progressEvent => {
                if (progressEvent.lengthComputable) {
                    let progress = (progressEvent.loaded / progressEvent.total);
                    console.log(`Uploaded ${progressEvent.loaded} of ${progressEvent.total}: ${progress}`);
                    toast.update(toastId, {progress: progress, autoClose: false});
                }
            })
            .then(resp => {
                if (resp.success) {
                    toast.update(toastId, {
                        progress: null,
                        type: toast.TYPE.SUCCESS,
                        render: t('toasts.library.uploaded'),
                        autoClose: 5000
                    });
                    // Refresh device metadata and packs list
                    dispatch(actionRefreshLibrary(t));
                } else {
                    toast.update(toastId, { type: toast.TYPE.ERROR, render: <IssueReportToast content={<>{t('toasts.library.uploadingFailed')}</>} />, autoClose: false });
                }
            })
            .catch(e => {
                console.error('failed to upload pack to library', e);
                toast.update(toastId, { type: toast.TYPE.ERROR, render: <IssueReportToast content={<>{t('toasts.library.uploadingFailed')}</>} error={e} />, autoClose: false });
            });
    }
};

export const actionConvertInLibrary = (uuid, path, format, allowEnriched, context, t) => {
    return dispatch => {
        let toastId = toast(t('toasts.library.converting'), { autoClose: false });
        return convertInLibrary(uuid, path, format, allowEnriched)
            .then(resp => {
                if (resp.success) {
                    console.log("Story pack converted. Path is: " + resp.path);
                    toast.update(toastId, {
                        progress: null,
                        type: toast.TYPE.SUCCESS,
                        render: t('toasts.library.converted'),
                        autoClose: 5000
                    });
                    // Refresh device metadata and packs list
                    dispatch(actionRefreshLibrary(t));
                    return resp.path;
                } else {
                    toast.update(toastId, { type: toast.TYPE.ERROR, render: <IssueReportToast content={<>{t('toasts.library.convertingFailed')}</>} />, autoClose: false });
                }
            })
            .catch(e => {
                console.error('failed to convert pack in library', e);
                toast.update(toastId, { type: toast.TYPE.ERROR, render: <IssueReportToast content={<>{t('toasts.library.convertingFailed')}</>} error={e} />, autoClose: false });
            });
    }
};

export const actionRemoveFromLibrary = (path, t) => {
    return dispatch => {
        let toastId = toast(t('toasts.library.removing'), { autoClose: false });
        return removeFromLibrary(path)
            .then(resp => {
                if (resp.success) {
                    toast.update(toastId, {type: toast.TYPE.SUCCESS, render: t('toasts.library.removed'), autoClose: 5000});
                    // Refresh library metadata and packs list
                    dispatch(actionRefreshLibrary(t));
                } else {
                    toast.update(toastId, { type: toast.TYPE.ERROR, render: <IssueReportToast content={<>{t('toasts.library.removingFailed')}</>} />, autoClose: false });
                }
            })
            .catch(e => {
                console.error('failed to remove pack from library', e);
                toast.update(toastId, { type: toast.TYPE.ERROR, render: <IssueReportToast content={<>{t('toasts.library.removingFailed')}</>} error={e} />, autoClose: false });
            });
    }
};

export const actionLoadEvergreen = (announceOptOut, t) => {
    return dispatch => {
        let toastId = toast(t('toasts.evergreen.loading'), { autoClose: false });
        return fetchEvergreenInfos()
            .then(infos => {
                dispatch(setApplicationVersion(infos.version));
                console.log("fetching latest release...");
                toast.update(toastId,{ render: t('toasts.evergreen.fetching') });
                return fetchEvergreenLatestRelease()
                    .then(latest => {
                        if (latest.name !== infos.version && Date.parse(latest.published_at) > Date.parse(infos.timestamp)) {
                            toast.update(toastId, { type: toast.TYPE.SUCCESS, render: <><p>{t('toasts.evergreen.fetched.newRelease.label')}</p><p><a href={latest.html_url}>{t('toasts.evergreen.fetched.newRelease.link', { version: latest.name })}</a></p></> });
                        } else {
                            toast.update(toastId, { type: toast.TYPE.INFO, render: t('toasts.evergreen.fetched.upToDate', { version: infos.version }), autoClose: 5000 });
                        }
                        // Check if the user opted-out
                        if (announceOptOut) {
                            console.log("user opted-out of announces. no need to fetch.");
                        } else {
                            console.log("fetching announce...");
                            return fetchEvergreenAnnounce()
                                .then(announce => {
                                    dispatch(setAnnounce(announce));
                                })
                                .catch(e => {
                                    console.error('failed to fetch announce', e);
                                });
                        }
                    })
                    .catch(e => {
                        console.error('failed to fetch latest release', e);
                        toast.update(toastId, { type: toast.TYPE.ERROR, render: <IssueReportToast content={<>{t('toasts.evergreen.fetchingFailed')}</>} error={e} />, autoClose: false });
                    });
            })
            .catch(e => {
                console.error('failed to fetch current version', e);
                toast.update(toastId, { type: toast.TYPE.ERROR, render: <IssueReportToast content={<>{t('toasts.evergreen.loadingFailed')}</>} error={e} />, autoClose: false });
            });
    }
};

export const devicePlugged = (metadata) => ({
    type: 'DEVICE_PLUGGED',
    metadata
});

export const deviceUnplugged = () => ({
    type: 'DEVICE_UNPLUGGED'
});

export const setDevicePacks = (packs) => ({
    type: 'SET_DEVICE_PACKS',
    packs: packs
});

export const showViewer = () => ({
    type: 'SHOW_VIEWER'
});

export const hideViewer = () => ({
    type: 'HIDE_VIEWER'
});

export const setViewerDiagram = (diagram) => ({
    type: 'SET_VIEWER_DIAGRAM',
    diagram
});

export const setViewerStage = (stage) => ({
    type: 'SET_VIEWER_STAGE',
    stage
});

export const setViewerAction = (action) => ({
    type: 'SET_VIEWER_ACTION',
    action
});

export const setViewerOptions = (options) => ({
    type: 'SET_VIEWER_OPTIONS',
    options
});

export const setEditorDiagram = (diagram, filename = null) => ({
    type: 'SET_EDITOR_DIAGRAM',
    diagram,
    filename
});

export const setEditorFilename = (filename = null) => ({
    type: 'SET_EDITOR_FILENAME',
    filename
});

export const setDiagramErrors= (errors) => ({
    type: 'SET_DIAGRAM_ERRORS',
    errors
});

export const showLibrary = () => ({
    type: 'SHOW_LIBRARY'
});

export const showEditor = () => ({
    type: 'SHOW_EDITOR'
});

export const setApplicationVersion = (version) => ({
    type: 'SET_APPLICATION_VERSION',
    version
});

export const setAnnounce = (announce) => ({
    type: 'SET_ANNOUNCE',
    announce
});

export const setAnnounceOptOut = (announceOptOut) => ({
    type: 'SET_ANNOUNCE_OPTOUT',
    announceOptOut
});

export const setAllowEnriched = (allowEnriched) => ({
    type: 'SET_ALLOW_ENRICHED',
    allowEnriched
});
