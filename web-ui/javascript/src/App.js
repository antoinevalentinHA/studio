/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

import React from 'react';
import { connect } from 'react-redux';
import { ToastContainer, toast } from 'react-toastify';
import {createEventBusChannel, CHANNEL_OPEN, CHANNEL_CLOSED} from './services/eventBusChannel';
import { withTranslation } from 'react-i18next';
import marked from 'marked';
import 'react-toastify/dist/ReactToastify.css';
import Switch from "react-switch";

import {AppContext} from './AppContext';
import Modal from './components/Modal';
import PackEditor from './components/diagram/PackEditor';
import PackLibrary from './components/PackLibrary';
import EditorPackViewer from "./components/viewer/EditorPackViewer";
import {simplifiedSample} from "./utils/sample";
import {
    actionCheckDevice,
    actionDevicePlugged,
    actionRefreshDevice,
    deviceUnplugged,
    actionLoadLibrary,
    setEditorDiagram,
    showLibrary,
    showEditor,
    actionLoadEvergreen,
    setAnnounceOptOut,
    setAllowEnriched
} from "./actions";
import {generateFilename} from "./utils/packs";
import {
    LOCAL_STORAGE_ANNOUNCE_LAST_SHOWN
} from "./utils/storage";

import './App.css';


/** Stable id so the channel notice updates in place instead of stacking on every reconnection. */
const CHANNEL_TOAST_ID = 'eventbus-channel';


class App extends React.Component {

    constructor(props) {
        super(props);
        this.state = {
            channel: null,
            channelConnected: null,
            shown: null,
            viewer: null,
            announce: null,
            showSettings: false
        };
    }

    componentDidMount() {
        const { t } = this.props;

        // The channel is created and fully wired synchronously. The previous code assigned `onopen`
        // from inside a setState callback, one React tick later; the underlying client fires onopen
        // at most once and only if it is already assigned, so a socket that opened during that tick
        // left the application with no device handlers at all for the lifetime of the page.
        console.log("Setting up vert.x event bus channel...");
        const channel = createEventBusChannel('http://localhost:8080/eventbus', {
            onStateChange: state => this.onChannelStateChange(state)
        });

        // Subscribing is safe before the socket is open: the channel records the subscription and
        // applies it on every (re)connection.
        channel.subscribe('storyteller.plugged', (error, message) => {
            console.log("Received `storyteller.plugged` event from vert.x event bus.");
            toast.info(t('toasts.device.monitoring.plugged'));
            this.props.onDevicePlugged(message.body);
        });
        channel.subscribe('storyteller.unplugged', (error, message) => {
            console.log("Received `storyteller.unplugged` event from vert.x event bus.");
            toast.info(t('toasts.device.monitoring.unplugged'));
            this.props.onDeviceUnplugged();
        });
        channel.subscribe('storyteller.failure', (error, message) => {
            console.log("Received `storyteller.failure` event from vert.x event bus.");
            toast.error(t('toasts.device.monitoring.failure'));
            this.props.onDeviceUnplugged();
        });

        this.setState({channel}, () => {
            // Check whether device is already plugged on startup
            this.props.checkDevice();

            // Load library on startup
            this.props.loadLibrary();

            // Load evergeen infos on startup
            this.props.loadEvergreen(this.props.settings.announceOptOut);

            // Load sample diagram in editor
            let model = simplifiedSample();
            this.props.setEditorDiagram(model, generateFilename(model));

            this.props.dispatchShowLibrary();
        });
    }

    onChannelStateChange = (state) => {
        const { t } = this.props;
        if (state === CHANNEL_CLOSED) {
            // Say what is actually true: the browser lost its notification channel. Whatever the
            // backend is doing with a transfer is unaffected, and must not be reported as failed.
            console.warn('event bus channel lost; reconnecting');
            this.setState({channelConnected: false});
            toast.warn(t('toasts.channel.lost'), {toastId: CHANNEL_TOAST_ID, autoClose: false});
        } else if (state === CHANNEL_OPEN) {
            if (this.state.channelConnected === false) {
                console.log('event bus channel restored; refreshing device state');
                toast.update(CHANNEL_TOAST_ID, {type: toast.TYPE.SUCCESS, render: t('toasts.channel.restored'), autoClose: 5000});
                // The backend may have published a verdict while the channel was down; those events
                // are not replayed. Re-reading the device is what settles the question.
                this.props.refreshDevice();
            }
            this.setState({channelConnected: true});
        }
    };

    componentWillUnmount() {
        if (this.state.channel) {
            this.state.channel.close();
        }
    }

    componentWillReceiveProps(nextProps, nextContext) {
        if (nextProps.evergreen.announce !== this.props.evergreen.announce && nextProps.evergreen.announce !== null) {
            // Check last announce display time in local storage and compare to announce time
            let announceTime = Date.parse(nextProps.evergreen.announce.date);
            let lastAnnounceShown = localStorage.getItem(LOCAL_STORAGE_ANNOUNCE_LAST_SHOWN) || 0;
            console.log('announce: ' + announceTime);
            console.log('last shown: ' + lastAnnounceShown);
            if (announceTime > lastAnnounceShown) {
                console.log('Announce must be displayed');
                this.setState({announce: nextProps.evergreen.announce.content})
            } else {
                console.log('Announce already displayed');
            }
        }
        this.setState({
            shown: nextProps.ui.shown,
            viewer: nextProps.viewer.show ? <EditorPackViewer/> : null
        });
    }

    showLibrary = () => {
        this.props.dispatchShowLibrary();
    };

    showEditor = () => {
        this.props.dispatchShowEditor();
    };

    dismissAnnounceDialog = () => {
        localStorage.setItem(LOCAL_STORAGE_ANNOUNCE_LAST_SHOWN, Date.now());
        this.setState({announce: null});
    };

    announceOptOut = () => {
        this.props.setAnnounceOptOut(true);
        this.dismissAnnounceDialog();
    };

    showSettings= () => {
        this.setState({showSettings: true});
    };

    dismissSettingsDialog = () => {
        this.setState({showSettings: false});
    };

    onAnnounceOptOutChanged = (announceOptOut) => {
        this.props.setAnnounceOptOut(announceOptOut);
    };

    onAllowEnrichedChanged = (allowEnriched) => {
        this.props.setAllowEnriched(allowEnriched);
    };

    render() {
        const { t, i18n } = this.props;
        return (
            <AppContext.Provider value={{channel: this.state.channel}}>
                <div className="App">
                    <ToastContainer/>
                    {this.state.announce && <Modal id={`announce-dialog`}
                                                   className="announce-dialog"
                                                   title={"\uD83E\uDD41 \uD83E\uDD41 \uD83E\uDD41"}
                                                   content={<div dangerouslySetInnerHTML={{__html: marked(this.state.announce)}} ></div>}
                                                   buttons={[
                                                       { label: t('dialogs.announce.optout'), onClick: this.announceOptOut },
                                                       { label: t('dialogs.shared.ok'), onClick: this.dismissAnnounceDialog }
                                                   ]}
                                                   onClose={this.dismissAnnounceDialog}
                    />}
                    {this.state.showSettings && <Modal id={`settings-dialog`}
                                                   className="settings-dialog"
                                                   title={t('dialogs.settings.title')}
                                                   content={<div>
                                                       <div><span>{t('dialogs.settings.announceOptOut')}</span><Switch onChange={this.onAnnounceOptOutChanged} checked={this.props.settings.announceOptOut} height={15} width={35} handleDiameter={20} boxShadow="0px 1px 5px rgba(0, 0, 0, 0.6)" activeBoxShadow="0px 0px 1px 10px rgba(0, 0, 0, 0.2)" uncheckedIcon={false} checkedIcon={false} /></div>
                                                       <div><span>{t('dialogs.settings.allowEnriched')}</span><Switch onChange={this.onAllowEnrichedChanged} checked={this.props.settings.allowEnriched} height={15} width={35} handleDiameter={20} boxShadow="0px 1px 5px rgba(0, 0, 0, 0.6)" activeBoxShadow="0px 0px 1px 10px rgba(0, 0, 0, 0.2)" uncheckedIcon={false} checkedIcon={false} /></div>
                                                   </div>}
                                                   buttons={[
                                                       { label: t('dialogs.shared.ok'), onClick: this.dismissSettingsDialog}
                                                   ]}
                                                   onClose={this.dismissSettingsDialog}
                    />}
                    {this.state.viewer}
                    <header className="App-header">
                        <div className="flags">
                            <span title="Français" role="img" aria-label="FR" onClick={() => i18n.changeLanguage('fr')}>🇫🇷&nbsp;</span>
                            <span title="English" role="img" aria-label="GB" onClick={() => i18n.changeLanguage('en')}>🇬🇧&nbsp;</span>
                            <span title={t('header.buttons.settings')} className="btn glyphicon glyphicon-wrench" onClick={this.showSettings}/>
                        </div>
                        <div  className="welcome">
                            {t('header.welcome')}
                            {this.props.evergreen.version && <span className="version"> ({this.props.evergreen.version})</span>}
                        </div>
                        <div className="controls">
                            <span title={t('header.buttons.library')} className={`btn glyphicon glyphicon-film ${this.state.shown === 'library' && 'active'}`} onClick={this.showLibrary}/>
                            <span title={t('header.buttons.editor')} className={`btn glyphicon glyphicon-edit ${this.state.shown === 'editor' && 'active'}`} onClick={this.showEditor}/>
                        </div>
                    </header>
                    {this.state.shown === 'library' && <PackLibrary/>}
                    {this.state.shown === 'editor' && <PackEditor/>}
                </div>
            </AppContext.Provider>
        );
    }
}

const mapStateToProps = (state, ownProps) => ({
    evergreen: state.evergreen,
    settings: state.settings,
    ui: state.ui,
    viewer: state.viewer
});

const mapDispatchToProps = (dispatch, ownProps) => ({
    checkDevice: () => dispatch(actionCheckDevice(ownProps.t)),
    onDevicePlugged: (metadata) => dispatch(actionDevicePlugged(metadata, ownProps.t)),
    onDeviceUnplugged: () => dispatch(deviceUnplugged()),
    refreshDevice: () => dispatch(actionRefreshDevice(ownProps.t)),
    loadLibrary: () => dispatch(actionLoadLibrary(ownProps.t)),
    setEditorDiagram: (diagram, filename) => dispatch(setEditorDiagram(diagram, filename)),
    dispatchShowLibrary: () => dispatch(showLibrary()),
    dispatchShowEditor: () => dispatch(showEditor()),
    loadEvergreen: (announceOptOut) => dispatch(actionLoadEvergreen(announceOptOut, ownProps.t)),
    setAnnounceOptOut: (announceOptOut) => dispatch(setAnnounceOptOut(announceOptOut)),
    setAllowEnriched: (allowEnriched) => dispatch(setAllowEnriched(allowEnriched))
});

export default withTranslation()(
    connect(
        mapStateToProps,
        mapDispatchToProps
    )(App)
)
