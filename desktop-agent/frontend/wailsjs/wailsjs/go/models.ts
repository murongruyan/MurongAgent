export namespace desktopbridge {
	
	export class RemoteADBDevice {
	    serial: string;
	    state: string;
	    model?: string;
	    product?: string;
	    device?: string;
	    transportId?: string;
	    authorized: boolean;
	
	    static createFrom(source: any = {}) {
	        return new RemoteADBDevice(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.serial = source["serial"];
	        this.state = source["state"];
	        this.model = source["model"];
	        this.product = source["product"];
	        this.device = source["device"];
	        this.transportId = source["transportId"];
	        this.authorized = source["authorized"];
	    }
	}
	export class RemoteBlockedPeer {
	    deviceId: string;
	    deviceDisplayId: string;
	    deviceName: string;
	    fingerprint: string;
	    blockedAt: number;
	
	    static createFrom(source: any = {}) {
	        return new RemoteBlockedPeer(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.deviceId = source["deviceId"];
	        this.deviceDisplayId = source["deviceDisplayId"];
	        this.deviceName = source["deviceName"];
	        this.fingerprint = source["fingerprint"];
	        this.blockedAt = source["blockedAt"];
	    }
	}
	export class RemoteConnectionRequest {
	    requestId: string;
	    deviceId: string;
	    deviceDisplayId: string;
	    deviceName: string;
	    platform: string;
	    fingerprint: string;
	    createdAt: number;
	
	    static createFrom(source: any = {}) {
	        return new RemoteConnectionRequest(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.requestId = source["requestId"];
	        this.deviceId = source["deviceId"];
	        this.deviceDisplayId = source["deviceDisplayId"];
	        this.deviceName = source["deviceName"];
	        this.platform = source["platform"];
	        this.fingerprint = source["fingerprint"];
	        this.createdAt = source["createdAt"];
	    }
	}
	export class RemoteDiscoveredDevice {
	    deviceId: string;
	    deviceDisplayId: string;
	    name: string;
	    platform: string;
	    url: string;
	    publicKey: string;
	    fingerprint: string;
	    lastSeenAt: number;
	
	    static createFrom(source: any = {}) {
	        return new RemoteDiscoveredDevice(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.deviceId = source["deviceId"];
	        this.deviceDisplayId = source["deviceDisplayId"];
	        this.name = source["name"];
	        this.platform = source["platform"];
	        this.url = source["url"];
	        this.publicKey = source["publicKey"];
	        this.fingerprint = source["fingerprint"];
	        this.lastSeenAt = source["lastSeenAt"];
	    }
	}
	export class RemoteNodeConfig {
	    connectionMode: string;
	    pairingAuthMethod: string;
	    phoneUrl: string;
	    adbSerial?: string;
	    workspace: string;
	    label: string;
	    clientName: string;
	    allowWrite: boolean;
	    shareDesktopTasks: boolean;
	    allowAgentControl: boolean;
	    terminalBackends: string[];
	    paired: boolean;
	    secureSyncReady: boolean;
	    deviceId?: string;
	    deviceDisplayId?: string;
	    deviceFingerprint?: string;
	    peerDeviceId?: string;
	    peerDeviceDisplayId?: string;
	    peerFingerprint?: string;
	
	    static createFrom(source: any = {}) {
	        return new RemoteNodeConfig(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.connectionMode = source["connectionMode"];
	        this.pairingAuthMethod = source["pairingAuthMethod"];
	        this.phoneUrl = source["phoneUrl"];
	        this.adbSerial = source["adbSerial"];
	        this.workspace = source["workspace"];
	        this.label = source["label"];
	        this.clientName = source["clientName"];
	        this.allowWrite = source["allowWrite"];
	        this.shareDesktopTasks = source["shareDesktopTasks"];
	        this.allowAgentControl = source["allowAgentControl"];
	        this.terminalBackends = source["terminalBackends"];
	        this.paired = source["paired"];
	        this.secureSyncReady = source["secureSyncReady"];
	        this.deviceId = source["deviceId"];
	        this.deviceDisplayId = source["deviceDisplayId"];
	        this.deviceFingerprint = source["deviceFingerprint"];
	        this.peerDeviceId = source["peerDeviceId"];
	        this.peerDeviceDisplayId = source["peerDeviceDisplayId"];
	        this.peerFingerprint = source["peerFingerprint"];
	    }
	}
	export class RemoteTerminal {
	    id: string;
	    label: string;
	    version?: string;
	
	    static createFrom(source: any = {}) {
	        return new RemoteTerminal(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.id = source["id"];
	        this.label = source["label"];
	        this.version = source["version"];
	    }
	}
	export class RemoteNodeStatus {
	    phase: string;
	    message: string;
	    running: boolean;
	
	    static createFrom(source: any = {}) {
	        return new RemoteNodeStatus(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.phase = source["phase"];
	        this.message = source["message"];
	        this.running = source["running"];
	    }
	}
	export class RemoteNodeSnapshot {
	    config: RemoteNodeConfig;
	    status: RemoteNodeStatus;
	    terminals: RemoteTerminal[];
	    connectionRequests: RemoteConnectionRequest[];
	    deviceServiceOnline: boolean;
	    temporaryCode?: string;
	    temporaryCodeExpiresAt?: number;
	    securityPasswordConfigured: boolean;
	    doNotDisturb: boolean;
	    blockedPeers: RemoteBlockedPeer[];
	    configPath: string;
	
	    static createFrom(source: any = {}) {
	        return new RemoteNodeSnapshot(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.config = this.convertValues(source["config"], RemoteNodeConfig);
	        this.status = this.convertValues(source["status"], RemoteNodeStatus);
	        this.terminals = this.convertValues(source["terminals"], RemoteTerminal);
	        this.connectionRequests = this.convertValues(source["connectionRequests"], RemoteConnectionRequest);
	        this.deviceServiceOnline = source["deviceServiceOnline"];
	        this.temporaryCode = source["temporaryCode"];
	        this.temporaryCodeExpiresAt = source["temporaryCodeExpiresAt"];
	        this.securityPasswordConfigured = source["securityPasswordConfigured"];
	        this.doNotDisturb = source["doNotDisturb"];
	        this.blockedPeers = this.convertValues(source["blockedPeers"], RemoteBlockedPeer);
	        this.configPath = source["configPath"];
	    }
	
		convertValues(a: any, classs: any, asMap: boolean = false): any {
		    if (!a) {
		        return a;
		    }
		    if (a.slice && a.map) {
		        return (a as any[]).map(elem => this.convertValues(elem, classs));
		    } else if ("object" === typeof a) {
		        if (asMap) {
		            for (const key of Object.keys(a)) {
		                a[key] = new classs(a[key]);
		            }
		            return a;
		        }
		        return new classs(a);
		    }
		    return a;
		}
	}
	

}

export namespace main {
	
	export class ApprovalDecision {
	    id: string;
	    approve: boolean;
	
	    static createFrom(source: any = {}) {
	        return new ApprovalDecision(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.id = source["id"];
	        this.approve = source["approve"];
	    }
	}
	export class AskAnswer {
	    questionId: string;
	    selectedOptions: string[];
	
	    static createFrom(source: any = {}) {
	        return new AskAnswer(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.questionId = source["questionId"];
	        this.selectedOptions = source["selectedOptions"];
	    }
	}
	export class AskDecision {
	    id: string;
	    answers?: AskAnswer[];
	    dismiss?: boolean;
	
	    static createFrom(source: any = {}) {
	        return new AskDecision(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.id = source["id"];
	        this.answers = this.convertValues(source["answers"], AskAnswer);
	        this.dismiss = source["dismiss"];
	    }
	
		convertValues(a: any, classs: any, asMap: boolean = false): any {
		    if (!a) {
		        return a;
		    }
		    if (a.slice && a.map) {
		        return (a as any[]).map(elem => this.convertValues(elem, classs));
		    } else if ("object" === typeof a) {
		        if (asMap) {
		            for (const key of Object.keys(a)) {
		                a[key] = new classs(a[key]);
		            }
		            return a;
		        }
		        return new classs(a);
		    }
		    return a;
		}
	}
	export class AskOption {
	    label: string;
	    description?: string;
	
	    static createFrom(source: any = {}) {
	        return new AskOption(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.label = source["label"];
	        this.description = source["description"];
	    }
	}
	export class AskQuestion {
	    id: string;
	    header?: string;
	    question: string;
	    options: AskOption[];
	    multiSelect?: boolean;
	
	    static createFrom(source: any = {}) {
	        return new AskQuestion(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.id = source["id"];
	        this.header = source["header"];
	        this.question = source["question"];
	        this.options = this.convertValues(source["options"], AskOption);
	        this.multiSelect = source["multiSelect"];
	    }
	
		convertValues(a: any, classs: any, asMap: boolean = false): any {
		    if (!a) {
		        return a;
		    }
		    if (a.slice && a.map) {
		        return (a as any[]).map(elem => this.convertValues(elem, classs));
		    } else if ("object" === typeof a) {
		        if (asMap) {
		            for (const key of Object.keys(a)) {
		                a[key] = new classs(a[key]);
		            }
		            return a;
		        }
		        return new classs(a);
		    }
		    return a;
		}
	}
	export class AskRequest {
	    id: string;
	    sessionId: string;
	    questions: AskQuestion[];
	    createdAt: number;
	
	    static createFrom(source: any = {}) {
	        return new AskRequest(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.id = source["id"];
	        this.sessionId = source["sessionId"];
	        this.questions = this.convertValues(source["questions"], AskQuestion);
	        this.createdAt = source["createdAt"];
	    }
	
		convertValues(a: any, classs: any, asMap: boolean = false): any {
		    if (!a) {
		        return a;
		    }
		    if (a.slice && a.map) {
		        return (a as any[]).map(elem => this.convertValues(elem, classs));
		    } else if ("object" === typeof a) {
		        if (asMap) {
		            for (const key of Object.keys(a)) {
		                a[key] = new classs(a[key]);
		            }
		            return a;
		        }
		        return new classs(a);
		    }
		    return a;
		}
	}
	export class CodexLoginInfo {
	    loginId?: string;
	    verificationUrl?: string;
	    userCode?: string;
	    waiting: boolean;
	
	    static createFrom(source: any = {}) {
	        return new CodexLoginInfo(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.loginId = source["loginId"];
	        this.verificationUrl = source["verificationUrl"];
	        this.userCode = source["userCode"];
	        this.waiting = source["waiting"];
	    }
	}
	export class CodexAccountProfile {
	    id: string;
	    label: string;
	    email?: string;
	    planType?: string;
	    enabled: boolean;
	    active: boolean;
	    loggedIn: boolean;
	    lowQuota: boolean;
	    rateLimits: CodexRateLimitInfo[];
	    lastCheckedAt?: number;
	    lastUsedAt?: number;
	    cooldownUntil?: number;
	    error?: string;
	
	    static createFrom(source: any = {}) {
	        return new CodexAccountProfile(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.id = source["id"];
	        this.label = source["label"];
	        this.email = source["email"];
	        this.planType = source["planType"];
	        this.enabled = source["enabled"];
	        this.active = source["active"];
	        this.loggedIn = source["loggedIn"];
	        this.lowQuota = source["lowQuota"];
	        this.rateLimits = this.convertValues(source["rateLimits"], CodexRateLimitInfo);
	        this.lastCheckedAt = source["lastCheckedAt"];
	        this.lastUsedAt = source["lastUsedAt"];
	        this.cooldownUntil = source["cooldownUntil"];
	        this.error = source["error"];
	    }
	
		convertValues(a: any, classs: any, asMap: boolean = false): any {
		    if (!a) {
		        return a;
		    }
		    if (a.slice && a.map) {
		        return (a as any[]).map(elem => this.convertValues(elem, classs));
		    } else if ("object" === typeof a) {
		        if (asMap) {
		            for (const key of Object.keys(a)) {
		                a[key] = new classs(a[key]);
		            }
		            return a;
		        }
		        return new classs(a);
		    }
		    return a;
		}
	}
	export class CodexAccountPoolSettings {
	    autoSwitch: boolean;
	    reservePercent: number;
	    cooldownMinutes: number;
	
	    static createFrom(source: any = {}) {
	        return new CodexAccountPoolSettings(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.autoSwitch = source["autoSwitch"];
	        this.reservePercent = source["reservePercent"];
	        this.cooldownMinutes = source["cooldownMinutes"];
	    }
	}
	export class CodexAccountPoolState {
	    activeAccountId: string;
	    settings: CodexAccountPoolSettings;
	    accounts: CodexAccountProfile[];
	
	    static createFrom(source: any = {}) {
	        return new CodexAccountPoolState(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.activeAccountId = source["activeAccountId"];
	        this.settings = this.convertValues(source["settings"], CodexAccountPoolSettings);
	        this.accounts = this.convertValues(source["accounts"], CodexAccountProfile);
	    }
	
		convertValues(a: any, classs: any, asMap: boolean = false): any {
		    if (!a) {
		        return a;
		    }
		    if (a.slice && a.map) {
		        return (a as any[]).map(elem => this.convertValues(elem, classs));
		    } else if ("object" === typeof a) {
		        if (asMap) {
		            for (const key of Object.keys(a)) {
		                a[key] = new classs(a[key]);
		            }
		            return a;
		        }
		        return new classs(a);
		    }
		    return a;
		}
	}
	export class CodexCreditsInfo {
	    hasCredits: boolean;
	    unlimited: boolean;
	    balance?: string;
	
	    static createFrom(source: any = {}) {
	        return new CodexCreditsInfo(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.hasCredits = source["hasCredits"];
	        this.unlimited = source["unlimited"];
	        this.balance = source["balance"];
	    }
	}
	export class CodexRateLimitWindow {
	    usedPercent: number;
	    windowDurationMins?: number;
	    resetsAt?: number;
	
	    static createFrom(source: any = {}) {
	        return new CodexRateLimitWindow(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.usedPercent = source["usedPercent"];
	        this.windowDurationMins = source["windowDurationMins"];
	        this.resetsAt = source["resetsAt"];
	    }
	}
	export class CodexRateLimitInfo {
	    limitId?: string;
	    limitName?: string;
	    planType?: string;
	    primary?: CodexRateLimitWindow;
	    secondary?: CodexRateLimitWindow;
	    credits?: CodexCreditsInfo;
	
	    static createFrom(source: any = {}) {
	        return new CodexRateLimitInfo(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.limitId = source["limitId"];
	        this.limitName = source["limitName"];
	        this.planType = source["planType"];
	        this.primary = this.convertValues(source["primary"], CodexRateLimitWindow);
	        this.secondary = this.convertValues(source["secondary"], CodexRateLimitWindow);
	        this.credits = this.convertValues(source["credits"], CodexCreditsInfo);
	    }
	
		convertValues(a: any, classs: any, asMap: boolean = false): any {
		    if (!a) {
		        return a;
		    }
		    if (a.slice && a.map) {
		        return (a as any[]).map(elem => this.convertValues(elem, classs));
		    } else if ("object" === typeof a) {
		        if (asMap) {
		            for (const key of Object.keys(a)) {
		                a[key] = new classs(a[key]);
		            }
		            return a;
		        }
		        return new classs(a);
		    }
		    return a;
		}
	}
	export class CodexModelInfo {
	    id: string;
	    model: string;
	    displayName: string;
	    description?: string;
	    defaultReasoningEffort?: string;
	    supportedReasoningEfforts: string[];
	    isDefault: boolean;
	
	    static createFrom(source: any = {}) {
	        return new CodexModelInfo(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.id = source["id"];
	        this.model = source["model"];
	        this.displayName = source["displayName"];
	        this.description = source["description"];
	        this.defaultReasoningEffort = source["defaultReasoningEffort"];
	        this.supportedReasoningEfforts = source["supportedReasoningEfforts"];
	        this.isDefault = source["isDefault"];
	    }
	}
	export class CodexRuntimeStatus {
	    available: boolean;
	    builtin: boolean;
	    running: boolean;
	    executablePath?: string;
	    version?: string;
	    loggedIn: boolean;
	    accountType?: string;
	    email?: string;
	    planType?: string;
	    requiresAuth: boolean;
	    models: CodexModelInfo[];
	    rateLimits: CodexRateLimitInfo[];
	    rateLimitError?: string;
	    accountPool: CodexAccountPoolState;
	    accountSwitchMessage?: string;
	    login: CodexLoginInfo;
	    error?: string;
	
	    static createFrom(source: any = {}) {
	        return new CodexRuntimeStatus(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.available = source["available"];
	        this.builtin = source["builtin"];
	        this.running = source["running"];
	        this.executablePath = source["executablePath"];
	        this.version = source["version"];
	        this.loggedIn = source["loggedIn"];
	        this.accountType = source["accountType"];
	        this.email = source["email"];
	        this.planType = source["planType"];
	        this.requiresAuth = source["requiresAuth"];
	        this.models = this.convertValues(source["models"], CodexModelInfo);
	        this.rateLimits = this.convertValues(source["rateLimits"], CodexRateLimitInfo);
	        this.rateLimitError = source["rateLimitError"];
	        this.accountPool = this.convertValues(source["accountPool"], CodexAccountPoolState);
	        this.accountSwitchMessage = source["accountSwitchMessage"];
	        this.login = this.convertValues(source["login"], CodexLoginInfo);
	        this.error = source["error"];
	    }
	
		convertValues(a: any, classs: any, asMap: boolean = false): any {
		    if (!a) {
		        return a;
		    }
		    if (a.slice && a.map) {
		        return (a as any[]).map(elem => this.convertValues(elem, classs));
		    } else if ("object" === typeof a) {
		        if (asMap) {
		            for (const key of Object.keys(a)) {
		                a[key] = new classs(a[key]);
		            }
		            return a;
		        }
		        return new classs(a);
		    }
		    return a;
		}
	}
	export class WorkspaceChangeState {
	    projectPath?: string;
	    watching: boolean;
	    pendingChanges: WorkspaceFileChange[];
	    recentChanges: WorkspaceFileChange[];
	    omittedCount: number;
	    error?: string;
	
	    static createFrom(source: any = {}) {
	        return new WorkspaceChangeState(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.projectPath = source["projectPath"];
	        this.watching = source["watching"];
	        this.pendingChanges = this.convertValues(source["pendingChanges"], WorkspaceFileChange);
	        this.recentChanges = this.convertValues(source["recentChanges"], WorkspaceFileChange);
	        this.omittedCount = source["omittedCount"];
	        this.error = source["error"];
	    }
	
		convertValues(a: any, classs: any, asMap: boolean = false): any {
		    if (!a) {
		        return a;
		    }
		    if (a.slice && a.map) {
		        return (a as any[]).map(elem => this.convertValues(elem, classs));
		    } else if ("object" === typeof a) {
		        if (asMap) {
		            for (const key of Object.keys(a)) {
		                a[key] = new classs(a[key]);
		            }
		            return a;
		        }
		        return new classs(a);
		    }
		    return a;
		}
	}
	export class DesktopBackupSettings {
	    dailyBackupEnabled: boolean;
	    maxBackupCount: number;
	
	    static createFrom(source: any = {}) {
	        return new DesktopBackupSettings(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.dailyBackupEnabled = source["dailyBackupEnabled"];
	        this.maxBackupCount = source["maxBackupCount"];
	    }
	}
	export class DesktopBackupStatus {
	    settings: DesktopBackupSettings;
	    lastBackupAt?: number;
	    lastBackupMessage?: string;
	    lastBackupFailed: boolean;
	    automaticBackupCount: number;
	    preRestoreSnapshotCount: number;
	    latestPreRestoreSnapshotName?: string;
	    storageLocation: string;
	    scheduleDescription: string;
	
	    static createFrom(source: any = {}) {
	        return new DesktopBackupStatus(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.settings = this.convertValues(source["settings"], DesktopBackupSettings);
	        this.lastBackupAt = source["lastBackupAt"];
	        this.lastBackupMessage = source["lastBackupMessage"];
	        this.lastBackupFailed = source["lastBackupFailed"];
	        this.automaticBackupCount = source["automaticBackupCount"];
	        this.preRestoreSnapshotCount = source["preRestoreSnapshotCount"];
	        this.latestPreRestoreSnapshotName = source["latestPreRestoreSnapshotName"];
	        this.storageLocation = source["storageLocation"];
	        this.scheduleDescription = source["scheduleDescription"];
	    }
	
		convertValues(a: any, classs: any, asMap: boolean = false): any {
		    if (!a) {
		        return a;
		    }
		    if (a.slice && a.map) {
		        return (a as any[]).map(elem => this.convertValues(elem, classs));
		    } else if ("object" === typeof a) {
		        if (asMap) {
		            for (const key of Object.keys(a)) {
		                a[key] = new classs(a[key]);
		            }
		            return a;
		        }
		        return new classs(a);
		    }
		    return a;
		}
	}
	export class SavedWorkflowRunRecord {
	    status: string;
	    startedAt?: number;
	    finishedAt?: number;
	    summary: string;
	    failureReason?: string;
	
	    static createFrom(source: any = {}) {
	        return new SavedWorkflowRunRecord(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.status = source["status"];
	        this.startedAt = source["startedAt"];
	        this.finishedAt = source["finishedAt"];
	        this.summary = source["summary"];
	        this.failureReason = source["failureReason"];
	    }
	}
	export class SavedWorkflowNode {
	    id: string;
	    label: string;
	    dependsOn: string[];
	    requiredPermission: string;
	    timeoutSeconds: number;
	    maxRetries: number;
	
	    static createFrom(source: any = {}) {
	        return new SavedWorkflowNode(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.id = source["id"];
	        this.label = source["label"];
	        this.dependsOn = source["dependsOn"];
	        this.requiredPermission = source["requiredPermission"];
	        this.timeoutSeconds = source["timeoutSeconds"];
	        this.maxRetries = source["maxRetries"];
	    }
	}
	export class SavedWorkflowDefinition {
	    id: string;
	    name: string;
	    template: string;
	    projectPath?: string;
	    githubRepository?: string;
	    nodes: SavedWorkflowNode[];
	    intervalMinutes: number;
	    enabled: boolean;
	    createdAt: number;
	    updatedAt: number;
	    lastRun?: SavedWorkflowRunRecord;
	
	    static createFrom(source: any = {}) {
	        return new SavedWorkflowDefinition(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.id = source["id"];
	        this.name = source["name"];
	        this.template = source["template"];
	        this.projectPath = source["projectPath"];
	        this.githubRepository = source["githubRepository"];
	        this.nodes = this.convertValues(source["nodes"], SavedWorkflowNode);
	        this.intervalMinutes = source["intervalMinutes"];
	        this.enabled = source["enabled"];
	        this.createdAt = source["createdAt"];
	        this.updatedAt = source["updatedAt"];
	        this.lastRun = this.convertValues(source["lastRun"], SavedWorkflowRunRecord);
	    }
	
		convertValues(a: any, classs: any, asMap: boolean = false): any {
		    if (!a) {
		        return a;
		    }
		    if (a.slice && a.map) {
		        return (a as any[]).map(elem => this.convertValues(elem, classs));
		    } else if ("object" === typeof a) {
		        if (asMap) {
		            for (const key of Object.keys(a)) {
		                a[key] = new classs(a[key]);
		            }
		            return a;
		        }
		        return new classs(a);
		    }
		    return a;
		}
	}
	export class PublicGitHubAccount {
	    id: string;
	    label: string;
	    login?: string;
	    apiBaseUrl: string;
	    hasToken: boolean;
	    active: boolean;
	    lastUsedAt?: number;
	
	    static createFrom(source: any = {}) {
	        return new PublicGitHubAccount(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.id = source["id"];
	        this.label = source["label"];
	        this.login = source["login"];
	        this.apiBaseUrl = source["apiBaseUrl"];
	        this.hasToken = source["hasToken"];
	        this.active = source["active"];
	        this.lastUsedAt = source["lastUsedAt"];
	    }
	}
	export class PublicGitHubConfig {
	    apiBaseUrl: string;
	    hasToken: boolean;
	    viewer?: string;
	
	    static createFrom(source: any = {}) {
	        return new PublicGitHubConfig(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.apiBaseUrl = source["apiBaseUrl"];
	        this.hasToken = source["hasToken"];
	        this.viewer = source["viewer"];
	    }
	}
	export class SavedWorkflowState {
	    github: PublicGitHubConfig;
	    githubAccounts: PublicGitHubAccount[];
	    activeGitHubAccount: string;
	    workflows: SavedWorkflowDefinition[];
	
	    static createFrom(source: any = {}) {
	        return new SavedWorkflowState(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.github = this.convertValues(source["github"], PublicGitHubConfig);
	        this.githubAccounts = this.convertValues(source["githubAccounts"], PublicGitHubAccount);
	        this.activeGitHubAccount = source["activeGitHubAccount"];
	        this.workflows = this.convertValues(source["workflows"], SavedWorkflowDefinition);
	    }
	
		convertValues(a: any, classs: any, asMap: boolean = false): any {
		    if (!a) {
		        return a;
		    }
		    if (a.slice && a.map) {
		        return (a as any[]).map(elem => this.convertValues(elem, classs));
		    } else if ("object" === typeof a) {
		        if (asMap) {
		            for (const key of Object.keys(a)) {
		                a[key] = new classs(a[key]);
		            }
		            return a;
		        }
		        return new classs(a);
		    }
		    return a;
		}
	}
	export class MCPToolInfo {
	    id: string;
	    name: string;
	    serverId: string;
	    serverName: string;
	    description?: string;
	    trustedReadOnly: boolean;
	
	    static createFrom(source: any = {}) {
	        return new MCPToolInfo(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.id = source["id"];
	        this.name = source["name"];
	        this.serverId = source["serverId"];
	        this.serverName = source["serverName"];
	        this.description = source["description"];
	        this.trustedReadOnly = source["trustedReadOnly"];
	    }
	}
	export class MCPServerStatus {
	    id: string;
	    name: string;
	    connected: boolean;
	    connecting: boolean;
	    toolCount: number;
	    toolNames: string[];
	    error?: string;
	    lastConnectedAt?: number;
	
	    static createFrom(source: any = {}) {
	        return new MCPServerStatus(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.id = source["id"];
	        this.name = source["name"];
	        this.connected = source["connected"];
	        this.connecting = source["connecting"];
	        this.toolCount = source["toolCount"];
	        this.toolNames = source["toolNames"];
	        this.error = source["error"];
	        this.lastConnectedAt = source["lastConnectedAt"];
	    }
	}
	export class PublicMCPServerConfig {
	    id: string;
	    name: string;
	    transport: string;
	    command?: string;
	    args: string[];
	    cwd?: string;
	    url?: string;
	    requestTimeoutSeconds: number;
	    trustedReadOnlyTools: string[];
	    enabled: boolean;
	    autoStart: boolean;
	    environmentKeys: string[];
	    headerKeys: string[];
	
	    static createFrom(source: any = {}) {
	        return new PublicMCPServerConfig(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.id = source["id"];
	        this.name = source["name"];
	        this.transport = source["transport"];
	        this.command = source["command"];
	        this.args = source["args"];
	        this.cwd = source["cwd"];
	        this.url = source["url"];
	        this.requestTimeoutSeconds = source["requestTimeoutSeconds"];
	        this.trustedReadOnlyTools = source["trustedReadOnlyTools"];
	        this.enabled = source["enabled"];
	        this.autoStart = source["autoStart"];
	        this.environmentKeys = source["environmentKeys"];
	        this.headerKeys = source["headerKeys"];
	    }
	}
	export class MCPState {
	    servers: PublicMCPServerConfig[];
	    statuses: MCPServerStatus[];
	    tools: MCPToolInfo[];
	
	    static createFrom(source: any = {}) {
	        return new MCPState(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.servers = this.convertValues(source["servers"], PublicMCPServerConfig);
	        this.statuses = this.convertValues(source["statuses"], MCPServerStatus);
	        this.tools = this.convertValues(source["tools"], MCPToolInfo);
	    }
	
		convertValues(a: any, classs: any, asMap: boolean = false): any {
		    if (!a) {
		        return a;
		    }
		    if (a.slice && a.map) {
		        return (a as any[]).map(elem => this.convertValues(elem, classs));
		    } else if ("object" === typeof a) {
		        if (asMap) {
		            for (const key of Object.keys(a)) {
		                a[key] = new classs(a[key]);
		            }
		            return a;
		        }
		        return new classs(a);
		    }
		    return a;
		}
	}
	export class ProjectSubagentTemplate {
	    id: string;
	    title: string;
	    description?: string;
	    goalMatchers?: string[];
	    preferredModel?: string;
	    preferredReasoningEffort?: string;
	    enableWebSearch: boolean;
	    allowWriteAccess: boolean;
	    allowCodeEdits: boolean;
	    allowShell: boolean;
	    enabled: boolean;
	
	    static createFrom(source: any = {}) {
	        return new ProjectSubagentTemplate(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.id = source["id"];
	        this.title = source["title"];
	        this.description = source["description"];
	        this.goalMatchers = source["goalMatchers"];
	        this.preferredModel = source["preferredModel"];
	        this.preferredReasoningEffort = source["preferredReasoningEffort"];
	        this.enableWebSearch = source["enableWebSearch"];
	        this.allowWriteAccess = source["allowWriteAccess"];
	        this.allowCodeEdits = source["allowCodeEdits"];
	        this.allowShell = source["allowShell"];
	        this.enabled = source["enabled"];
	    }
	}
	export class ToolPreferences {
	    approvalMode: string;
	    allowlist: string[];
	    enabledBuiltinTools: string[];
	    enabledFileOperations: string[];
	    inheritGlobal?: boolean;
	    subagentTemplates?: ProjectSubagentTemplate[];
	
	    static createFrom(source: any = {}) {
	        return new ToolPreferences(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.approvalMode = source["approvalMode"];
	        this.allowlist = source["allowlist"];
	        this.enabledBuiltinTools = source["enabledBuiltinTools"];
	        this.enabledFileOperations = source["enabledFileOperations"];
	        this.inheritGlobal = source["inheritGlobal"];
	        this.subagentTemplates = this.convertValues(source["subagentTemplates"], ProjectSubagentTemplate);
	    }
	
		convertValues(a: any, classs: any, asMap: boolean = false): any {
		    if (!a) {
		        return a;
		    }
		    if (a.slice && a.map) {
		        return (a as any[]).map(elem => this.convertValues(elem, classs));
		    } else if ("object" === typeof a) {
		        if (asMap) {
		            for (const key of Object.keys(a)) {
		                a[key] = new classs(a[key]);
		            }
		            return a;
		        }
		        return new classs(a);
		    }
		    return a;
		}
	}
	export class ProjectToolPreferencesSnapshot {
	    projectPath: string;
	    projectLabel: string;
	    hasProject: boolean;
	    usesGlobal: boolean;
	    preferences: ToolPreferences;
	
	    static createFrom(source: any = {}) {
	        return new ProjectToolPreferencesSnapshot(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.projectPath = source["projectPath"];
	        this.projectLabel = source["projectLabel"];
	        this.hasProject = source["hasProject"];
	        this.usesGlobal = source["usesGlobal"];
	        this.preferences = this.convertValues(source["preferences"], ToolPreferences);
	    }
	
		convertValues(a: any, classs: any, asMap: boolean = false): any {
		    if (!a) {
		        return a;
		    }
		    if (a.slice && a.map) {
		        return (a as any[]).map(elem => this.convertValues(elem, classs));
		    } else if ("object" === typeof a) {
		        if (asMap) {
		            for (const key of Object.keys(a)) {
		                a[key] = new classs(a[key]);
		            }
		            return a;
		        }
		        return new classs(a);
		    }
		    return a;
		}
	}
	export class ProjectKnowledgeSnapshot {
	    projectPath: string;
	    projectLabel: string;
	    hasProject: boolean;
	    library: KnowledgeLibrary;
	
	    static createFrom(source: any = {}) {
	        return new ProjectKnowledgeSnapshot(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.projectPath = source["projectPath"];
	        this.projectLabel = source["projectLabel"];
	        this.hasProject = source["hasProject"];
	        this.library = this.convertValues(source["library"], KnowledgeLibrary);
	    }
	
		convertValues(a: any, classs: any, asMap: boolean = false): any {
		    if (!a) {
		        return a;
		    }
		    if (a.slice && a.map) {
		        return (a as any[]).map(elem => this.convertValues(elem, classs));
		    } else if ("object" === typeof a) {
		        if (asMap) {
		            for (const key of Object.keys(a)) {
		                a[key] = new classs(a[key]);
		            }
		            return a;
		        }
		        return new classs(a);
		    }
		    return a;
		}
	}
	export class GlobalSkill {
	    id: string;
	    title: string;
	    description: string;
	    content: string;
	    runAs: string;
	    allowedTools: string[];
	    preferredModel: string;
	    enabled: boolean;
	
	    static createFrom(source: any = {}) {
	        return new GlobalSkill(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.id = source["id"];
	        this.title = source["title"];
	        this.description = source["description"];
	        this.content = source["content"];
	        this.runAs = source["runAs"];
	        this.allowedTools = source["allowedTools"];
	        this.preferredModel = source["preferredModel"];
	        this.enabled = source["enabled"];
	    }
	}
	export class GlobalMemory {
	    id: string;
	    title: string;
	    content: string;
	    enabled: boolean;
	
	    static createFrom(source: any = {}) {
	        return new GlobalMemory(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.id = source["id"];
	        this.title = source["title"];
	        this.content = source["content"];
	        this.enabled = source["enabled"];
	    }
	}
	export class GlobalRule {
	    id: string;
	    title: string;
	    content: string;
	    enabled: boolean;
	
	    static createFrom(source: any = {}) {
	        return new GlobalRule(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.id = source["id"];
	        this.title = source["title"];
	        this.content = source["content"];
	        this.enabled = source["enabled"];
	    }
	}
	export class KnowledgeLibrary {
	    rules: GlobalRule[];
	    memories: GlobalMemory[];
	    skills: GlobalSkill[];
	
	    static createFrom(source: any = {}) {
	        return new KnowledgeLibrary(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.rules = this.convertValues(source["rules"], GlobalRule);
	        this.memories = this.convertValues(source["memories"], GlobalMemory);
	        this.skills = this.convertValues(source["skills"], GlobalSkill);
	    }
	
		convertValues(a: any, classs: any, asMap: boolean = false): any {
		    if (!a) {
		        return a;
		    }
		    if (a.slice && a.map) {
		        return (a as any[]).map(elem => this.convertValues(elem, classs));
		    } else if ("object" === typeof a) {
		        if (asMap) {
		            for (const key of Object.keys(a)) {
		                a[key] = new classs(a[key]);
		            }
		            return a;
		        }
		        return new classs(a);
		    }
		    return a;
		}
	}
	export class TerminalBackend {
	    id: string;
	    label: string;
	    version?: string;
	
	    static createFrom(source: any = {}) {
	        return new TerminalBackend(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.id = source["id"];
	        this.label = source["label"];
	        this.version = source["version"];
	    }
	}
	export class SessionExecutionHandoff {
	    version: number;
	    owner: string;
	    token: string;
	    baseMessageCount: number;
	    baseDigest: string;
	    startedAt: number;
	
	    static createFrom(source: any = {}) {
	        return new SessionExecutionHandoff(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.version = source["version"];
	        this.owner = source["owner"];
	        this.token = source["token"];
	        this.baseMessageCount = source["baseMessageCount"];
	        this.baseDigest = source["baseDigest"];
	        this.startedAt = source["startedAt"];
	    }
	}
	export class subagentTaskResult {
	    index: number;
	    label: string;
	    goal: string;
	    dependsOn?: number[];
	    templateId?: string;
	    templateTitle?: string;
	    status: string;
	    output?: string;
	    error?: string;
	
	    static createFrom(source: any = {}) {
	        return new subagentTaskResult(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.index = source["index"];
	        this.label = source["label"];
	        this.goal = source["goal"];
	        this.dependsOn = source["dependsOn"];
	        this.templateId = source["templateId"];
	        this.templateTitle = source["templateTitle"];
	        this.status = source["status"];
	        this.output = source["output"];
	        this.error = source["error"];
	    }
	}
	export class SubagentBackgroundJob {
	    id: string;
	    label: string;
	    parentGoal: string;
	    status: string;
	    statusMessage?: string;
	    taskCount: number;
	    completed?: number;
	    failed?: number;
	    skipped?: number;
	    cancelled?: number;
	    results?: subagentTaskResult[];
	    createdAt: number;
	    startedAt?: number;
	    finishedAt?: number;
	
	    static createFrom(source: any = {}) {
	        return new SubagentBackgroundJob(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.id = source["id"];
	        this.label = source["label"];
	        this.parentGoal = source["parentGoal"];
	        this.status = source["status"];
	        this.statusMessage = source["statusMessage"];
	        this.taskCount = source["taskCount"];
	        this.completed = source["completed"];
	        this.failed = source["failed"];
	        this.skipped = source["skipped"];
	        this.cancelled = source["cancelled"];
	        this.results = this.convertValues(source["results"], subagentTaskResult);
	        this.createdAt = source["createdAt"];
	        this.startedAt = source["startedAt"];
	        this.finishedAt = source["finishedAt"];
	    }
	
		convertValues(a: any, classs: any, asMap: boolean = false): any {
		    if (!a) {
		        return a;
		    }
		    if (a.slice && a.map) {
		        return (a as any[]).map(elem => this.convertValues(elem, classs));
		    } else if ("object" === typeof a) {
		        if (asMap) {
		            for (const key of Object.keys(a)) {
		                a[key] = new classs(a[key]);
		            }
		            return a;
		        }
		        return new classs(a);
		    }
		    return a;
		}
	}
	export class WorkspaceReviewFile {
	    path: string;
	    kind: string;
	    additions: number;
	    deletions: number;
	    binary?: boolean;
	
	    static createFrom(source: any = {}) {
	        return new WorkspaceReviewFile(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.path = source["path"];
	        this.kind = source["kind"];
	        this.additions = source["additions"];
	        this.deletions = source["deletions"];
	        this.binary = source["binary"];
	    }
	}
	export class WorkspaceReview {
	    id: string;
	    projectPath: string;
	    projectPrefix?: string;
	    beforeTree: string;
	    afterTree: string;
	    files: WorkspaceReviewFile[];
	    additions: number;
	    deletions: number;
	    binaryFiles?: number;
	    createdAt: number;
	    undoAvailable: boolean;
	    undone?: boolean;
	    statusMessage?: string;
	
	    static createFrom(source: any = {}) {
	        return new WorkspaceReview(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.id = source["id"];
	        this.projectPath = source["projectPath"];
	        this.projectPrefix = source["projectPrefix"];
	        this.beforeTree = source["beforeTree"];
	        this.afterTree = source["afterTree"];
	        this.files = this.convertValues(source["files"], WorkspaceReviewFile);
	        this.additions = source["additions"];
	        this.deletions = source["deletions"];
	        this.binaryFiles = source["binaryFiles"];
	        this.createdAt = source["createdAt"];
	        this.undoAvailable = source["undoAvailable"];
	        this.undone = source["undone"];
	        this.statusMessage = source["statusMessage"];
	    }
	
		convertValues(a: any, classs: any, asMap: boolean = false): any {
		    if (!a) {
		        return a;
		    }
		    if (a.slice && a.map) {
		        return (a as any[]).map(elem => this.convertValues(elem, classs));
		    } else if ("object" === typeof a) {
		        if (asMap) {
		            for (const key of Object.keys(a)) {
		                a[key] = new classs(a[key]);
		            }
		            return a;
		        }
		        return new classs(a);
		    }
		    return a;
		}
	}
	export class WorkspaceFileChange {
	    path: string;
	    kind: string;
	    changedAt: number;
	    directory?: boolean;
	    size?: number;
	
	    static createFrom(source: any = {}) {
	        return new WorkspaceFileChange(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.path = source["path"];
	        this.kind = source["kind"];
	        this.changedAt = source["changedAt"];
	        this.directory = source["directory"];
	        this.size = source["size"];
	    }
	}
	export class ComposerContextItem {
	    kind: string;
	    id?: string;
	    label?: string;
	    path?: string;
	    scope?: string;
	
	    static createFrom(source: any = {}) {
	        return new ComposerContextItem(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.kind = source["kind"];
	        this.id = source["id"];
	        this.label = source["label"];
	        this.path = source["path"];
	        this.scope = source["scope"];
	    }
	}
	export class ImageGenerationMessage {
	    prompt: string;
	    providerProfileId?: string;
	    model?: string;
	    status: string;
	    stage?: string;
	    error?: string;
	    operation?: string;
	    sourceMessageId?: string;
	    createdAt: number;
	
	    static createFrom(source: any = {}) {
	        return new ImageGenerationMessage(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.prompt = source["prompt"];
	        this.providerProfileId = source["providerProfileId"];
	        this.model = source["model"];
	        this.status = source["status"];
	        this.stage = source["stage"];
	        this.error = source["error"];
	        this.operation = source["operation"];
	        this.sourceMessageId = source["sourceMessageId"];
	        this.createdAt = source["createdAt"];
	    }
	}
	export class MessageImageAttachment {
	    id: string;
	    fileName: string;
	    mimeType: string;
	    cacheFile: string;
	    width?: number;
	    height?: number;
	    sizeBytes: number;
	    highResolution?: boolean;
	
	    static createFrom(source: any = {}) {
	        return new MessageImageAttachment(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.id = source["id"];
	        this.fileName = source["fileName"];
	        this.mimeType = source["mimeType"];
	        this.cacheFile = source["cacheFile"];
	        this.width = source["width"];
	        this.height = source["height"];
	        this.sizeBytes = source["sizeBytes"];
	        this.highResolution = source["highResolution"];
	    }
	}
	export class ChatMessage {
	    id: string;
	    role: string;
	    content: string;
	    goal?: string;
	    reasoning?: string;
	    imageAttachments?: MessageImageAttachment[];
	    imageAnalysis?: string;
	    imageGeneration?: ImageGenerationMessage;
	    createdAt: number;
	    kind?: string;
	    toolName?: string;
	    toolCallId?: string;
	    toolArguments?: string;
	    toolStatus?: string;
	    context?: ComposerContextItem[];
	    mode?: string;
	    workspaceChanges?: WorkspaceFileChange[];
	    workspaceChangesOmitted?: number;
	    workspaceReview?: WorkspaceReview;
	
	    static createFrom(source: any = {}) {
	        return new ChatMessage(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.id = source["id"];
	        this.role = source["role"];
	        this.content = source["content"];
	        this.goal = source["goal"];
	        this.reasoning = source["reasoning"];
	        this.imageAttachments = this.convertValues(source["imageAttachments"], MessageImageAttachment);
	        this.imageAnalysis = source["imageAnalysis"];
	        this.imageGeneration = this.convertValues(source["imageGeneration"], ImageGenerationMessage);
	        this.createdAt = source["createdAt"];
	        this.kind = source["kind"];
	        this.toolName = source["toolName"];
	        this.toolCallId = source["toolCallId"];
	        this.toolArguments = source["toolArguments"];
	        this.toolStatus = source["toolStatus"];
	        this.context = this.convertValues(source["context"], ComposerContextItem);
	        this.mode = source["mode"];
	        this.workspaceChanges = this.convertValues(source["workspaceChanges"], WorkspaceFileChange);
	        this.workspaceChangesOmitted = source["workspaceChangesOmitted"];
	        this.workspaceReview = this.convertValues(source["workspaceReview"], WorkspaceReview);
	    }
	
		convertValues(a: any, classs: any, asMap: boolean = false): any {
		    if (!a) {
		        return a;
		    }
		    if (a.slice && a.map) {
		        return (a as any[]).map(elem => this.convertValues(elem, classs));
		    } else if ("object" === typeof a) {
		        if (asMap) {
		            for (const key of Object.keys(a)) {
		                a[key] = new classs(a[key]);
		            }
		            return a;
		        }
		        return new classs(a);
		    }
		    return a;
		}
	}
	export class SessionCompression {
	    version?: number;
	    summary?: string;
	    sourceMessageCount?: number;
	    sourceEndMessageId?: string;
	    createdAt?: number;
	    active?: boolean;
	    method?: string;
	
	    static createFrom(source: any = {}) {
	        return new SessionCompression(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.version = source["version"];
	        this.summary = source["summary"];
	        this.sourceMessageCount = source["sourceMessageCount"];
	        this.sourceEndMessageId = source["sourceEndMessageId"];
	        this.createdAt = source["createdAt"];
	        this.active = source["active"];
	        this.method = source["method"];
	    }
	}
	export class SessionUsage {
	    modelRequests?: number;
	    reportedUsageRequests?: number;
	    inputTokens?: number;
	    outputTokens?: number;
	    totalTokens?: number;
	    cachedInputTokens?: number;
	    reasoningOutputTokens?: number;
	    lastProviderProfileId?: string;
	    lastProviderId?: string;
	    lastModel?: string;
	
	    static createFrom(source: any = {}) {
	        return new SessionUsage(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.modelRequests = source["modelRequests"];
	        this.reportedUsageRequests = source["reportedUsageRequests"];
	        this.inputTokens = source["inputTokens"];
	        this.outputTokens = source["outputTokens"];
	        this.totalTokens = source["totalTokens"];
	        this.cachedInputTokens = source["cachedInputTokens"];
	        this.reasoningOutputTokens = source["reasoningOutputTokens"];
	        this.lastProviderProfileId = source["lastProviderProfileId"];
	        this.lastProviderId = source["lastProviderId"];
	        this.lastModel = source["lastModel"];
	    }
	}
	export class WorkflowStepSignOff {
	    stepIndex: number;
	    step: string;
	    reportedStep: string;
	    resultSummary: string;
	    matchedEvidence: number;
	    totalEvidence: number;
	    matchedToolNames?: string[];
	    evidenceMessageIds?: string[];
	    signedOffAt: number;
	
	    static createFrom(source: any = {}) {
	        return new WorkflowStepSignOff(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.stepIndex = source["stepIndex"];
	        this.step = source["step"];
	        this.reportedStep = source["reportedStep"];
	        this.resultSummary = source["resultSummary"];
	        this.matchedEvidence = source["matchedEvidence"];
	        this.totalEvidence = source["totalEvidence"];
	        this.matchedToolNames = source["matchedToolNames"];
	        this.evidenceMessageIds = source["evidenceMessageIds"];
	        this.signedOffAt = source["signedOffAt"];
	    }
	}
	export class DesktopWorkflowPlan {
	    id: string;
	    goal: string;
	    summary: string;
	    steps: string[];
	    currentStepIndex: number;
	    status: string;
	    nextStepHint?: string;
	    rawPlan?: string;
	    sourceMessageId: string;
	    stepSignOffs?: WorkflowStepSignOff[];
	    createdAt: number;
	    executionStartedAt?: number;
	    updatedAt: number;
	
	    static createFrom(source: any = {}) {
	        return new DesktopWorkflowPlan(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.id = source["id"];
	        this.goal = source["goal"];
	        this.summary = source["summary"];
	        this.steps = source["steps"];
	        this.currentStepIndex = source["currentStepIndex"];
	        this.status = source["status"];
	        this.nextStepHint = source["nextStepHint"];
	        this.rawPlan = source["rawPlan"];
	        this.sourceMessageId = source["sourceMessageId"];
	        this.stepSignOffs = this.convertValues(source["stepSignOffs"], WorkflowStepSignOff);
	        this.createdAt = source["createdAt"];
	        this.executionStartedAt = source["executionStartedAt"];
	        this.updatedAt = source["updatedAt"];
	    }
	
		convertValues(a: any, classs: any, asMap: boolean = false): any {
		    if (!a) {
		        return a;
		    }
		    if (a.slice && a.map) {
		        return (a as any[]).map(elem => this.convertValues(elem, classs));
		    } else if ("object" === typeof a) {
		        if (asMap) {
		            for (const key of Object.keys(a)) {
		                a[key] = new classs(a[key]);
		            }
		            return a;
		        }
		        return new classs(a);
		    }
		    return a;
		}
	}
	export class ChatSession {
	    id: string;
	    title: string;
	    createdAt: number;
	    updatedAt: number;
	    syncOriginPlatform?: string;
	    syncOriginSessionId?: string;
	    projectPath?: string;
	    goal?: string;
	    goalStatus?: string;
	    planModeEnabled?: boolean;
	    workflowPlan?: DesktopWorkflowPlan;
	    usage?: SessionUsage;
	    compression?: SessionCompression;
	    codexThreadId?: string;
	    codexSyncedMessageId?: string;
	    codexToolsVersion?: number;
	    codexAccountId?: string;
	    codexAccountPinned?: boolean;
	    messages: ChatMessage[];
	    backgroundSubagentJobs?: SubagentBackgroundJob[];
	    executionHandoff?: SessionExecutionHandoff;
	
	    static createFrom(source: any = {}) {
	        return new ChatSession(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.id = source["id"];
	        this.title = source["title"];
	        this.createdAt = source["createdAt"];
	        this.updatedAt = source["updatedAt"];
	        this.syncOriginPlatform = source["syncOriginPlatform"];
	        this.syncOriginSessionId = source["syncOriginSessionId"];
	        this.projectPath = source["projectPath"];
	        this.goal = source["goal"];
	        this.goalStatus = source["goalStatus"];
	        this.planModeEnabled = source["planModeEnabled"];
	        this.workflowPlan = this.convertValues(source["workflowPlan"], DesktopWorkflowPlan);
	        this.usage = this.convertValues(source["usage"], SessionUsage);
	        this.compression = this.convertValues(source["compression"], SessionCompression);
	        this.codexThreadId = source["codexThreadId"];
	        this.codexSyncedMessageId = source["codexSyncedMessageId"];
	        this.codexToolsVersion = source["codexToolsVersion"];
	        this.codexAccountId = source["codexAccountId"];
	        this.codexAccountPinned = source["codexAccountPinned"];
	        this.messages = this.convertValues(source["messages"], ChatMessage);
	        this.backgroundSubagentJobs = this.convertValues(source["backgroundSubagentJobs"], SubagentBackgroundJob);
	        this.executionHandoff = this.convertValues(source["executionHandoff"], SessionExecutionHandoff);
	    }
	
		convertValues(a: any, classs: any, asMap: boolean = false): any {
		    if (!a) {
		        return a;
		    }
		    if (a.slice && a.map) {
		        return (a as any[]).map(elem => this.convertValues(elem, classs));
		    } else if ("object" === typeof a) {
		        if (asMap) {
		            for (const key of Object.keys(a)) {
		                a[key] = new classs(a[key]);
		            }
		            return a;
		        }
		        return new classs(a);
		    }
		    return a;
		}
	}
	export class SessionSummary {
	    id: string;
	    title: string;
	    updatedAt: number;
	    messageCount: number;
	    projectPath?: string;
	    executionOwner: string;
	    handoffStartedAt?: number;
	
	    static createFrom(source: any = {}) {
	        return new SessionSummary(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.id = source["id"];
	        this.title = source["title"];
	        this.updatedAt = source["updatedAt"];
	        this.messageCount = source["messageCount"];
	        this.projectPath = source["projectPath"];
	        this.executionOwner = source["executionOwner"];
	        this.handoffStartedAt = source["handoffStartedAt"];
	    }
	}
	export class RecentProject {
	    path: string;
	    name: string;
	    lastOpenedAt: number;
	    exists: boolean;
	
	    static createFrom(source: any = {}) {
	        return new RecentProject(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.path = source["path"];
	        this.name = source["name"];
	        this.lastOpenedAt = source["lastOpenedAt"];
	        this.exists = source["exists"];
	    }
	}
	export class PublicProviderProfile {
	    id: string;
	    providerId: string;
	    name: string;
	    baseUrl: string;
	    model: string;
	    reasoningEffort: string;
	    apiMode: string;
	    contextWindowTokens?: number;
	    executablePath?: string;
	    hasApiKey: boolean;
	
	    static createFrom(source: any = {}) {
	        return new PublicProviderProfile(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.id = source["id"];
	        this.providerId = source["providerId"];
	        this.name = source["name"];
	        this.baseUrl = source["baseUrl"];
	        this.model = source["model"];
	        this.reasoningEffort = source["reasoningEffort"];
	        this.apiMode = source["apiMode"];
	        this.contextWindowTokens = source["contextWindowTokens"];
	        this.executablePath = source["executablePath"];
	        this.hasApiKey = source["hasApiKey"];
	    }
	}
	export class PublicDesktopConfig {
	    projectPath: string;
	    baseUrl: string;
	    model: string;
	    hasApiKey: boolean;
	    approvalMode: string;
	    allowlist: string[];
	    maxToolIterations: number;
	    systemPrompt: string;
	    responseVerbosity: string;
	    temperature: number;
	    maxTokens: number;
	    enableMultimodalMessages: boolean;
	    guiInferenceMode: string;
	    guiLocalBaseUrl: string;
	    guiLocalModel: string;
	    guiAllowRemoteSemanticTree: boolean;
	    guiAllowRemoteScreenshots: boolean;
	    guiAllowRemoteFullScreen: boolean;
	    visionRoutingEnabled: boolean;
	    visionProviderProfileId?: string;
	    visionModel?: string;
	    visionCustomBaseUrl?: string;
	    visionHasApiKey: boolean;
	    imageGenerationProviderProfileId?: string;
	    imageGenerationModel?: string;
	    imageGenerationCustomBaseUrl?: string;
	    imageGenerationHasApiKey: boolean;
	    imageGenerationSize?: string;
	    imageGenerationQuality?: string;
	    imageGenerationFormat?: string;
	    imageGenerationCompression?: number;
	    imageGenerationPartialImages?: number;
	    imageUpscaleBaseUrl?: string;
	    imageUpscaleModel?: string;
	    imageUpscaleHasApiKey: boolean;
	    imageUpscaleScale?: number;
	    plannerProfileEnabled: boolean;
	    plannerModel: string;
	    plannerReasoningEffort: string;
	    subagentDefaultProfileEnabled: boolean;
	    subagentDefaultModel: string;
	    subagentDefaultReasoningEffort: string;
	    activeProviderProfileId: string;
	    providerProfiles: PublicProviderProfile[];
	    enabledBuiltinTools: string[];
	    enabledFileOperations: string[];
	    recentProjects: RecentProject[];
	
	    static createFrom(source: any = {}) {
	        return new PublicDesktopConfig(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.projectPath = source["projectPath"];
	        this.baseUrl = source["baseUrl"];
	        this.model = source["model"];
	        this.hasApiKey = source["hasApiKey"];
	        this.approvalMode = source["approvalMode"];
	        this.allowlist = source["allowlist"];
	        this.maxToolIterations = source["maxToolIterations"];
	        this.systemPrompt = source["systemPrompt"];
	        this.responseVerbosity = source["responseVerbosity"];
	        this.temperature = source["temperature"];
	        this.maxTokens = source["maxTokens"];
	        this.enableMultimodalMessages = source["enableMultimodalMessages"];
	        this.guiInferenceMode = source["guiInferenceMode"];
	        this.guiLocalBaseUrl = source["guiLocalBaseUrl"];
	        this.guiLocalModel = source["guiLocalModel"];
	        this.guiAllowRemoteSemanticTree = source["guiAllowRemoteSemanticTree"];
	        this.guiAllowRemoteScreenshots = source["guiAllowRemoteScreenshots"];
	        this.guiAllowRemoteFullScreen = source["guiAllowRemoteFullScreen"];
	        this.visionRoutingEnabled = source["visionRoutingEnabled"];
	        this.visionProviderProfileId = source["visionProviderProfileId"];
	        this.visionModel = source["visionModel"];
	        this.visionCustomBaseUrl = source["visionCustomBaseUrl"];
	        this.visionHasApiKey = source["visionHasApiKey"];
	        this.imageGenerationProviderProfileId = source["imageGenerationProviderProfileId"];
	        this.imageGenerationModel = source["imageGenerationModel"];
	        this.imageGenerationCustomBaseUrl = source["imageGenerationCustomBaseUrl"];
	        this.imageGenerationHasApiKey = source["imageGenerationHasApiKey"];
	        this.imageGenerationSize = source["imageGenerationSize"];
	        this.imageGenerationQuality = source["imageGenerationQuality"];
	        this.imageGenerationFormat = source["imageGenerationFormat"];
	        this.imageGenerationCompression = source["imageGenerationCompression"];
	        this.imageGenerationPartialImages = source["imageGenerationPartialImages"];
	        this.imageUpscaleBaseUrl = source["imageUpscaleBaseUrl"];
	        this.imageUpscaleModel = source["imageUpscaleModel"];
	        this.imageUpscaleHasApiKey = source["imageUpscaleHasApiKey"];
	        this.imageUpscaleScale = source["imageUpscaleScale"];
	        this.plannerProfileEnabled = source["plannerProfileEnabled"];
	        this.plannerModel = source["plannerModel"];
	        this.plannerReasoningEffort = source["plannerReasoningEffort"];
	        this.subagentDefaultProfileEnabled = source["subagentDefaultProfileEnabled"];
	        this.subagentDefaultModel = source["subagentDefaultModel"];
	        this.subagentDefaultReasoningEffort = source["subagentDefaultReasoningEffort"];
	        this.activeProviderProfileId = source["activeProviderProfileId"];
	        this.providerProfiles = this.convertValues(source["providerProfiles"], PublicProviderProfile);
	        this.enabledBuiltinTools = source["enabledBuiltinTools"];
	        this.enabledFileOperations = source["enabledFileOperations"];
	        this.recentProjects = this.convertValues(source["recentProjects"], RecentProject);
	    }
	
		convertValues(a: any, classs: any, asMap: boolean = false): any {
		    if (!a) {
		        return a;
		    }
		    if (a.slice && a.map) {
		        return (a as any[]).map(elem => this.convertValues(elem, classs));
		    } else if ("object" === typeof a) {
		        if (asMap) {
		            for (const key of Object.keys(a)) {
		                a[key] = new classs(a[key]);
		            }
		            return a;
		        }
		        return new classs(a);
		    }
		    return a;
		}
	}
	export class DesktopPlatformInfo {
	    os: string;
	    architecture: string;
	    label: string;
	    credentialProtection: string;
	    packageKind: string;
	    version: string;
	
	    static createFrom(source: any = {}) {
	        return new DesktopPlatformInfo(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.os = source["os"];
	        this.architecture = source["architecture"];
	        this.label = source["label"];
	        this.credentialProtection = source["credentialProtection"];
	        this.packageKind = source["packageKind"];
	        this.version = source["version"];
	    }
	}
	export class BootstrapState {
	    platform: DesktopPlatformInfo;
	    config: PublicDesktopConfig;
	    sessions: SessionSummary[];
	    activeSession?: ChatSession;
	    activeProjectAvailable: boolean;
	    activeProjectError?: string;
	    remoteNode: desktopbridge.RemoteNodeSnapshot;
	    terminals: TerminalBackend[];
	    knowledge: KnowledgeLibrary;
	    projectKnowledge: ProjectKnowledgeSnapshot;
	    projectTools: ProjectToolPreferencesSnapshot;
	    mcp: MCPState;
	    savedWorkflows: SavedWorkflowState;
	    backup: DesktopBackupStatus;
	    workspaceChanges: WorkspaceChangeState;
	    codex: CodexRuntimeStatus;
	
	    static createFrom(source: any = {}) {
	        return new BootstrapState(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.platform = this.convertValues(source["platform"], DesktopPlatformInfo);
	        this.config = this.convertValues(source["config"], PublicDesktopConfig);
	        this.sessions = this.convertValues(source["sessions"], SessionSummary);
	        this.activeSession = this.convertValues(source["activeSession"], ChatSession);
	        this.activeProjectAvailable = source["activeProjectAvailable"];
	        this.activeProjectError = source["activeProjectError"];
	        this.remoteNode = this.convertValues(source["remoteNode"], desktopbridge.RemoteNodeSnapshot);
	        this.terminals = this.convertValues(source["terminals"], TerminalBackend);
	        this.knowledge = this.convertValues(source["knowledge"], KnowledgeLibrary);
	        this.projectKnowledge = this.convertValues(source["projectKnowledge"], ProjectKnowledgeSnapshot);
	        this.projectTools = this.convertValues(source["projectTools"], ProjectToolPreferencesSnapshot);
	        this.mcp = this.convertValues(source["mcp"], MCPState);
	        this.savedWorkflows = this.convertValues(source["savedWorkflows"], SavedWorkflowState);
	        this.backup = this.convertValues(source["backup"], DesktopBackupStatus);
	        this.workspaceChanges = this.convertValues(source["workspaceChanges"], WorkspaceChangeState);
	        this.codex = this.convertValues(source["codex"], CodexRuntimeStatus);
	    }
	
		convertValues(a: any, classs: any, asMap: boolean = false): any {
		    if (!a) {
		        return a;
		    }
		    if (a.slice && a.map) {
		        return (a as any[]).map(elem => this.convertValues(elem, classs));
		    } else if ("object" === typeof a) {
		        if (asMap) {
		            for (const key of Object.keys(a)) {
		                a[key] = new classs(a[key]);
		            }
		            return a;
		        }
		        return new classs(a);
		    }
		    return a;
		}
	}
	export class BuiltinVisionReasoningMode {
	    id: string;
	    displayName: string;
	
	    static createFrom(source: any = {}) {
	        return new BuiltinVisionReasoningMode(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.id = source["id"];
	        this.displayName = source["displayName"];
	    }
	}
	export class BuiltinVisionModelInfo {
	    tier: string;
	    displayName: string;
	    engine: string;
	    sizeBytes: number;
	    installed: boolean;
	    active: boolean;
	    available: boolean;
	    unavailableReason?: string;
	    recommendation: string;
	    supportsVision: boolean;
	    reasoningModes: BuiltinVisionReasoningMode[];
	    defaultReasoningMode?: string;
	
	    static createFrom(source: any = {}) {
	        return new BuiltinVisionModelInfo(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.tier = source["tier"];
	        this.displayName = source["displayName"];
	        this.engine = source["engine"];
	        this.sizeBytes = source["sizeBytes"];
	        this.installed = source["installed"];
	        this.active = source["active"];
	        this.available = source["available"];
	        this.unavailableReason = source["unavailableReason"];
	        this.recommendation = source["recommendation"];
	        this.supportsVision = source["supportsVision"];
	        this.reasoningModes = this.convertValues(source["reasoningModes"], BuiltinVisionReasoningMode);
	        this.defaultReasoningMode = source["defaultReasoningMode"];
	    }
	
		convertValues(a: any, classs: any, asMap: boolean = false): any {
		    if (!a) {
		        return a;
		    }
		    if (a.slice && a.map) {
		        return (a as any[]).map(elem => this.convertValues(elem, classs));
		    } else if ("object" === typeof a) {
		        if (asMap) {
		            for (const key of Object.keys(a)) {
		                a[key] = new classs(a[key]);
		            }
		            return a;
		        }
		        return new classs(a);
		    }
		    return a;
		}
	}
	export class BuiltinVisionModelStatus {
	    models: BuiltinVisionModelInfo[];
	    installingTier?: string;
	    downloadedBytes: number;
	    totalBytes: number;
	    message?: string;
	    error?: string;
	    deviceRecommendation: string;
	
	    static createFrom(source: any = {}) {
	        return new BuiltinVisionModelStatus(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.models = this.convertValues(source["models"], BuiltinVisionModelInfo);
	        this.installingTier = source["installingTier"];
	        this.downloadedBytes = source["downloadedBytes"];
	        this.totalBytes = source["totalBytes"];
	        this.message = source["message"];
	        this.error = source["error"];
	        this.deviceRecommendation = source["deviceRecommendation"];
	    }
	
		convertValues(a: any, classs: any, asMap: boolean = false): any {
		    if (!a) {
		        return a;
		    }
		    if (a.slice && a.map) {
		        return (a as any[]).map(elem => this.convertValues(elem, classs));
		    } else if ("object" === typeof a) {
		        if (asMap) {
		            for (const key of Object.keys(a)) {
		                a[key] = new classs(a[key]);
		            }
		            return a;
		        }
		        return new classs(a);
		    }
		    return a;
		}
	}
	
	export class CancelSubagentJobRequest {
	    sessionId: string;
	    jobId: string;
	
	    static createFrom(source: any = {}) {
	        return new CancelSubagentJobRequest(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.sessionId = source["sessionId"];
	        this.jobId = source["jobId"];
	    }
	}
	export class ChatImagePreviewRequest {
	    sessionId: string;
	    messageId: string;
	    imageId: string;
	
	    static createFrom(source: any = {}) {
	        return new ChatImagePreviewRequest(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.sessionId = source["sessionId"];
	        this.messageId = source["messageId"];
	        this.imageId = source["imageId"];
	    }
	}
	
	
	export class CodexAccountMutationRequest {
	    accountId: string;
	    label?: string;
	    enabled?: boolean;
	
	    static createFrom(source: any = {}) {
	        return new CodexAccountMutationRequest(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.accountId = source["accountId"];
	        this.label = source["label"];
	        this.enabled = source["enabled"];
	    }
	}
	
	export class CodexAccountPoolSettingsRequest {
	    autoSwitch: boolean;
	    reservePercent: number;
	    cooldownMinutes: number;
	
	    static createFrom(source: any = {}) {
	        return new CodexAccountPoolSettingsRequest(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.autoSwitch = source["autoSwitch"];
	        this.reservePercent = source["reservePercent"];
	        this.cooldownMinutes = source["cooldownMinutes"];
	    }
	}
	
	
	export class CodexAccountRuntimeRequest {
	    executablePath: string;
	    accountId?: string;
	    label?: string;
	
	    static createFrom(source: any = {}) {
	        return new CodexAccountRuntimeRequest(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.executablePath = source["executablePath"];
	        this.accountId = source["accountId"];
	        this.label = source["label"];
	    }
	}
	
	
	
	
	
	export class CodexRuntimeRequest {
	    executablePath: string;
	
	    static createFrom(source: any = {}) {
	        return new CodexRuntimeRequest(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.executablePath = source["executablePath"];
	    }
	}
	
	export class CodexSessionAccountPinRequest {
	    sessionId: string;
	    pinned: boolean;
	
	    static createFrom(source: any = {}) {
	        return new CodexSessionAccountPinRequest(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.sessionId = source["sessionId"];
	        this.pinned = source["pinned"];
	    }
	}
	export class ComposerChoice {
	    kind: string;
	    id: string;
	    label: string;
	    detail?: string;
	    scope?: string;
	    disabled?: boolean;
	
	    static createFrom(source: any = {}) {
	        return new ComposerChoice(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.kind = source["kind"];
	        this.id = source["id"];
	        this.label = source["label"];
	        this.detail = source["detail"];
	        this.scope = source["scope"];
	        this.disabled = source["disabled"];
	    }
	}
	export class ComposerCatalog {
	    skills: ComposerChoice[];
	    subagents: ComposerChoice[];
	    mcpTools: ComposerChoice[];
	
	    static createFrom(source: any = {}) {
	        return new ComposerCatalog(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.skills = this.convertValues(source["skills"], ComposerChoice);
	        this.subagents = this.convertValues(source["subagents"], ComposerChoice);
	        this.mcpTools = this.convertValues(source["mcpTools"], ComposerChoice);
	    }
	
		convertValues(a: any, classs: any, asMap: boolean = false): any {
		    if (!a) {
		        return a;
		    }
		    if (a.slice && a.map) {
		        return (a as any[]).map(elem => this.convertValues(elem, classs));
		    } else if ("object" === typeof a) {
		        if (asMap) {
		            for (const key of Object.keys(a)) {
		                a[key] = new classs(a[key]);
		            }
		            return a;
		        }
		        return new classs(a);
		    }
		    return a;
		}
	}
	
	
	export class ConfirmProviderImportRequest {
	    requestId: string;
	    activate: boolean;
	    enableUsage: boolean;
	
	    static createFrom(source: any = {}) {
	        return new ConfirmProviderImportRequest(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.requestId = source["requestId"];
	        this.activate = source["activate"];
	        this.enableUsage = source["enableUsage"];
	    }
	}
	export class CreateProjectRequest {
	    parentPath: string;
	    name: string;
	
	    static createFrom(source: any = {}) {
	        return new CreateProjectRequest(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.parentPath = source["parentPath"];
	        this.name = source["name"];
	    }
	}
	export class DesktopBackupEntry {
	    path: string;
	    category: string;
	    sizeBytes: number;
	    sha256: string;
	
	    static createFrom(source: any = {}) {
	        return new DesktopBackupEntry(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.path = source["path"];
	        this.category = source["category"];
	        this.sizeBytes = source["sizeBytes"];
	        this.sha256 = source["sha256"];
	    }
	}
	export class DesktopBackupManifest {
	    format: string;
	    formatVersion: number;
	    createdAtEpochMillis: number;
	    appVersionName: string;
	    appVersionCode: number;
	    kind: string;
	    entries: DesktopBackupEntry[];
	    excludedByDefault: string[];
	
	    static createFrom(source: any = {}) {
	        return new DesktopBackupManifest(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.format = source["format"];
	        this.formatVersion = source["formatVersion"];
	        this.createdAtEpochMillis = source["createdAtEpochMillis"];
	        this.appVersionName = source["appVersionName"];
	        this.appVersionCode = source["appVersionCode"];
	        this.kind = source["kind"];
	        this.entries = this.convertValues(source["entries"], DesktopBackupEntry);
	        this.excludedByDefault = source["excludedByDefault"];
	    }
	
		convertValues(a: any, classs: any, asMap: boolean = false): any {
		    if (!a) {
		        return a;
		    }
		    if (a.slice && a.map) {
		        return (a as any[]).map(elem => this.convertValues(elem, classs));
		    } else if ("object" === typeof a) {
		        if (asMap) {
		            for (const key of Object.keys(a)) {
		                a[key] = new classs(a[key]);
		            }
		            return a;
		        }
		        return new classs(a);
		    }
		    return a;
		}
	}
	export class DesktopBackupOperationResult {
	    manifest?: DesktopBackupManifest;
	    message: string;
	    outputPath?: string;
	    restoredEntryCount?: number;
	    preRestoreSnapshotName?: string;
	    skipped: boolean;
	    status: DesktopBackupStatus;
	
	    static createFrom(source: any = {}) {
	        return new DesktopBackupOperationResult(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.manifest = this.convertValues(source["manifest"], DesktopBackupManifest);
	        this.message = source["message"];
	        this.outputPath = source["outputPath"];
	        this.restoredEntryCount = source["restoredEntryCount"];
	        this.preRestoreSnapshotName = source["preRestoreSnapshotName"];
	        this.skipped = source["skipped"];
	        this.status = this.convertValues(source["status"], DesktopBackupStatus);
	    }
	
		convertValues(a: any, classs: any, asMap: boolean = false): any {
		    if (!a) {
		        return a;
		    }
		    if (a.slice && a.map) {
		        return (a as any[]).map(elem => this.convertValues(elem, classs));
		    } else if ("object" === typeof a) {
		        if (asMap) {
		            for (const key of Object.keys(a)) {
		                a[key] = new classs(a[key]);
		            }
		            return a;
		        }
		        return new classs(a);
		    }
		    return a;
		}
	}
	
	
	
	export class DesktopRestoreSelection {
	    path: string;
	    fileName: string;
	    sizeBytes: number;
	
	    static createFrom(source: any = {}) {
	        return new DesktopRestoreSelection(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.path = source["path"];
	        this.fileName = source["fileName"];
	        this.sizeBytes = source["sizeBytes"];
	    }
	}
	export class DesktopUpdateInfo {
	    currentVersion: string;
	    latestVersion?: string;
	    updateAvailable: boolean;
	    releaseUrl?: string;
	    downloadUrl?: string;
	    packageName?: string;
	    publishedAt?: string;
	
	    static createFrom(source: any = {}) {
	        return new DesktopUpdateInfo(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.currentVersion = source["currentVersion"];
	        this.latestVersion = source["latestVersion"];
	        this.updateAvailable = source["updateAvailable"];
	        this.releaseUrl = source["releaseUrl"];
	        this.downloadUrl = source["downloadUrl"];
	        this.packageName = source["packageName"];
	        this.publishedAt = source["publishedAt"];
	    }
	}
	
	export class DiscardChatImageRequest {
	    imageId: string;
	    cacheFile: string;
	
	    static createFrom(source: any = {}) {
	        return new DiscardChatImageRequest(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.imageId = source["imageId"];
	        this.cacheFile = source["cacheFile"];
	    }
	}
	export class ExportSessionRequest {
	    sessionId: string;
	    format: string;
	
	    static createFrom(source: any = {}) {
	        return new ExportSessionRequest(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.sessionId = source["sessionId"];
	        this.format = source["format"];
	    }
	}
	export class GenerateImageRequest {
	    sessionId: string;
	    prompt: string;
	
	    static createFrom(source: any = {}) {
	        return new GenerateImageRequest(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.sessionId = source["sessionId"];
	        this.prompt = source["prompt"];
	    }
	}
	export class GitHubAccountRequest {
	    accountId?: string;
	    label?: string;
	
	    static createFrom(source: any = {}) {
	        return new GitHubAccountRequest(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.accountId = source["accountId"];
	        this.label = source["label"];
	    }
	}
	
	
	
	
	
	
	
	
	
	export class ProjectAuditEntry {
	    id: string;
	    projectPath: string;
	    sessionId?: string;
	    sessionTitle?: string;
	    source: string;
	    action: string;
	    outcome: string;
	    toolName?: string;
	    summary: string;
	    paths?: string[];
	    createdAt: number;
	    occurrences: number;
	
	    static createFrom(source: any = {}) {
	        return new ProjectAuditEntry(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.id = source["id"];
	        this.projectPath = source["projectPath"];
	        this.sessionId = source["sessionId"];
	        this.sessionTitle = source["sessionTitle"];
	        this.source = source["source"];
	        this.action = source["action"];
	        this.outcome = source["outcome"];
	        this.toolName = source["toolName"];
	        this.summary = source["summary"];
	        this.paths = source["paths"];
	        this.createdAt = source["createdAt"];
	        this.occurrences = source["occurrences"];
	    }
	}
	export class ProjectAuditExportResult {
	    path?: string;
	    entryCount: number;
	    message: string;
	    skipped: boolean;
	
	    static createFrom(source: any = {}) {
	        return new ProjectAuditExportResult(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.path = source["path"];
	        this.entryCount = source["entryCount"];
	        this.message = source["message"];
	        this.skipped = source["skipped"];
	    }
	}
	export class ProjectAuditPage {
	    projectPath?: string;
	    entries: ProjectAuditEntry[];
	    totalCount: number;
	    filteredCount: number;
	    hasMore: boolean;
	    nextBeforeAt?: number;
	    nextBeforeId?: string;
	    storageError?: string;
	    archiveVersion: number;
	
	    static createFrom(source: any = {}) {
	        return new ProjectAuditPage(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.projectPath = source["projectPath"];
	        this.entries = this.convertValues(source["entries"], ProjectAuditEntry);
	        this.totalCount = source["totalCount"];
	        this.filteredCount = source["filteredCount"];
	        this.hasMore = source["hasMore"];
	        this.nextBeforeAt = source["nextBeforeAt"];
	        this.nextBeforeId = source["nextBeforeId"];
	        this.storageError = source["storageError"];
	        this.archiveVersion = source["archiveVersion"];
	    }
	
		convertValues(a: any, classs: any, asMap: boolean = false): any {
		    if (!a) {
		        return a;
		    }
		    if (a.slice && a.map) {
		        return (a as any[]).map(elem => this.convertValues(elem, classs));
		    } else if ("object" === typeof a) {
		        if (asMap) {
		            for (const key of Object.keys(a)) {
		                a[key] = new classs(a[key]);
		            }
		            return a;
		        }
		        return new classs(a);
		    }
		    return a;
		}
	}
	export class ProjectAuditQuery {
	    search?: string;
	    source?: string;
	    limit?: number;
	    beforeAt?: number;
	    beforeId?: string;
	
	    static createFrom(source: any = {}) {
	        return new ProjectAuditQuery(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.search = source["search"];
	        this.source = source["source"];
	        this.limit = source["limit"];
	        this.beforeAt = source["beforeAt"];
	        this.beforeId = source["beforeId"];
	    }
	}
	export class ProjectEntry {
	    path: string;
	    name: string;
	    directory: boolean;
	    size?: number;
	
	    static createFrom(source: any = {}) {
	        return new ProjectEntry(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.path = source["path"];
	        this.name = source["name"];
	        this.directory = source["directory"];
	        this.size = source["size"];
	    }
	}
	
	
	
	export class providerImportUsageRule {
	    endpoint: string;
	    sourceLabel: string;
	    intervalMinutes: number;
	
	    static createFrom(source: any = {}) {
	        return new providerImportUsageRule(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.endpoint = source["endpoint"];
	        this.sourceLabel = source["sourceLabel"];
	        this.intervalMinutes = source["intervalMinutes"];
	    }
	}
	export class ProviderImportPreview {
	    requestId: string;
	    sourceScheme: string;
	    appLabel: string;
	    name: string;
	    homepage?: string;
	    endpoints: string[];
	    maskedApiKey: string;
	    model?: string;
	    notes?: string;
	    requestedActive: boolean;
	    usageScript?: string;
	    requestedUsageEnabled: boolean;
	    usageRule?: providerImportUsageRule;
	
	    static createFrom(source: any = {}) {
	        return new ProviderImportPreview(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.requestId = source["requestId"];
	        this.sourceScheme = source["sourceScheme"];
	        this.appLabel = source["appLabel"];
	        this.name = source["name"];
	        this.homepage = source["homepage"];
	        this.endpoints = source["endpoints"];
	        this.maskedApiKey = source["maskedApiKey"];
	        this.model = source["model"];
	        this.notes = source["notes"];
	        this.requestedActive = source["requestedActive"];
	        this.usageScript = source["usageScript"];
	        this.requestedUsageEnabled = source["requestedUsageEnabled"];
	        this.usageRule = this.convertValues(source["usageRule"], providerImportUsageRule);
	    }
	
		convertValues(a: any, classs: any, asMap: boolean = false): any {
		    if (!a) {
		        return a;
		    }
		    if (a.slice && a.map) {
		        return (a as any[]).map(elem => this.convertValues(elem, classs));
		    } else if ("object" === typeof a) {
		        if (asMap) {
		            for (const key of Object.keys(a)) {
		                a[key] = new classs(a[key]);
		            }
		            return a;
		        }
		        return new classs(a);
		    }
		    return a;
		}
	}
	export class ProviderImportProtocolState {
	    supported: boolean;
	    murongRegistered: boolean;
	    ccSwitchCompatibilityEnabled: boolean;
	    ccSwitchCurrentHandlerLabel: string;
	
	    static createFrom(source: any = {}) {
	        return new ProviderImportProtocolState(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.supported = source["supported"];
	        this.murongRegistered = source["murongRegistered"];
	        this.ccSwitchCompatibilityEnabled = source["ccSwitchCompatibilityEnabled"];
	        this.ccSwitchCurrentHandlerLabel = source["ccSwitchCurrentHandlerLabel"];
	    }
	}
	export class ProviderUsageStatus {
	    providerProfileId: string;
	    endpoint: string;
	    intervalMinutes: number;
	    enabled: boolean;
	    remaining?: number;
	    unit?: string;
	    lastSyncedAt?: number;
	    nextSyncAt?: number;
	    lastError?: string;
	    syncing: boolean;
	
	    static createFrom(source: any = {}) {
	        return new ProviderUsageStatus(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.providerProfileId = source["providerProfileId"];
	        this.endpoint = source["endpoint"];
	        this.intervalMinutes = source["intervalMinutes"];
	        this.enabled = source["enabled"];
	        this.remaining = source["remaining"];
	        this.unit = source["unit"];
	        this.lastSyncedAt = source["lastSyncedAt"];
	        this.nextSyncAt = source["nextSyncAt"];
	        this.lastError = source["lastError"];
	        this.syncing = source["syncing"];
	    }
	}
	export class ProviderUsageState {
	    items: ProviderUsageStatus[];
	
	    static createFrom(source: any = {}) {
	        return new ProviderUsageState(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.items = this.convertValues(source["items"], ProviderUsageStatus);
	    }
	
		convertValues(a: any, classs: any, asMap: boolean = false): any {
		    if (!a) {
		        return a;
		    }
		    if (a.slice && a.map) {
		        return (a as any[]).map(elem => this.convertValues(elem, classs));
		    } else if ("object" === typeof a) {
		        if (asMap) {
		            for (const key of Object.keys(a)) {
		                a[key] = new classs(a[key]);
		            }
		            return a;
		        }
		        return new classs(a);
		    }
		    return a;
		}
	}
	export class ProviderImportResult {
	    config: PublicDesktopConfig;
	    usage: ProviderUsageState;
	
	    static createFrom(source: any = {}) {
	        return new ProviderImportResult(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.config = this.convertValues(source["config"], PublicDesktopConfig);
	        this.usage = this.convertValues(source["usage"], ProviderUsageState);
	    }
	
		convertValues(a: any, classs: any, asMap: boolean = false): any {
		    if (!a) {
		        return a;
		    }
		    if (a.slice && a.map) {
		        return (a as any[]).map(elem => this.convertValues(elem, classs));
		    } else if ("object" === typeof a) {
		        if (asMap) {
		            for (const key of Object.keys(a)) {
		                a[key] = new classs(a[key]);
		            }
		            return a;
		        }
		        return new classs(a);
		    }
		    return a;
		}
	}
	export class ProviderModelCatalogRequest {
	    providerProfileId: string;
	    providerId: string;
	    baseUrl: string;
	    currentModel: string;
	    apiKey: string;
	    clearApiKey: boolean;
	
	    static createFrom(source: any = {}) {
	        return new ProviderModelCatalogRequest(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.providerProfileId = source["providerProfileId"];
	        this.providerId = source["providerId"];
	        this.baseUrl = source["baseUrl"];
	        this.currentModel = source["currentModel"];
	        this.apiKey = source["apiKey"];
	        this.clearApiKey = source["clearApiKey"];
	    }
	}
	export class ProviderModelCatalogResult {
	    models: string[];
	    sourceLabel: string;
	    syncedAt: number;
	
	    static createFrom(source: any = {}) {
	        return new ProviderModelCatalogResult(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.models = source["models"];
	        this.sourceLabel = source["sourceLabel"];
	        this.syncedAt = source["syncedAt"];
	    }
	}
	
	
	
	
	
	
	
	
	export class RenameSessionRequest {
	    sessionId: string;
	    title: string;
	
	    static createFrom(source: any = {}) {
	        return new RenameSessionRequest(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.sessionId = source["sessionId"];
	        this.title = source["title"];
	    }
	}
	export class RestoreDesktopBackupRequest {
	    path: string;
	    confirmed: boolean;
	
	    static createFrom(source: any = {}) {
	        return new RestoreDesktopBackupRequest(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.path = source["path"];
	        this.confirmed = source["confirmed"];
	    }
	}
	export class RunSavedWorkflowRequest {
	    id: string;
	    sessionId: string;
	    confirmed: boolean;
	
	    static createFrom(source: any = {}) {
	        return new RunSavedWorkflowRequest(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.id = source["id"];
	        this.sessionId = source["sessionId"];
	        this.confirmed = source["confirmed"];
	    }
	}
	export class SaveGitHubConfigRequest {
	    apiBaseUrl: string;
	    token: string;
	    clearToken: boolean;
	
	    static createFrom(source: any = {}) {
	        return new SaveGitHubConfigRequest(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.apiBaseUrl = source["apiBaseUrl"];
	        this.token = source["token"];
	        this.clearToken = source["clearToken"];
	    }
	}
	export class SaveMCPServerConfig {
	    id: string;
	    name: string;
	    transport: string;
	    command: string;
	    args: string[];
	    cwd: string;
	    url: string;
	    requestTimeoutSeconds: number;
	    trustedReadOnlyTools: string[];
	    enabled: boolean;
	    autoStart: boolean;
	    environment?: Record<string, string>;
	    headers?: Record<string, string>;
	    clearEnvironment: boolean;
	    clearHeaders: boolean;
	
	    static createFrom(source: any = {}) {
	        return new SaveMCPServerConfig(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.id = source["id"];
	        this.name = source["name"];
	        this.transport = source["transport"];
	        this.command = source["command"];
	        this.args = source["args"];
	        this.cwd = source["cwd"];
	        this.url = source["url"];
	        this.requestTimeoutSeconds = source["requestTimeoutSeconds"];
	        this.trustedReadOnlyTools = source["trustedReadOnlyTools"];
	        this.enabled = source["enabled"];
	        this.autoStart = source["autoStart"];
	        this.environment = source["environment"];
	        this.headers = source["headers"];
	        this.clearEnvironment = source["clearEnvironment"];
	        this.clearHeaders = source["clearHeaders"];
	    }
	}
	export class SaveMCPServersRequest {
	    servers: SaveMCPServerConfig[];
	
	    static createFrom(source: any = {}) {
	        return new SaveMCPServersRequest(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.servers = this.convertValues(source["servers"], SaveMCPServerConfig);
	    }
	
		convertValues(a: any, classs: any, asMap: boolean = false): any {
		    if (!a) {
		        return a;
		    }
		    if (a.slice && a.map) {
		        return (a as any[]).map(elem => this.convertValues(elem, classs));
		    } else if ("object" === typeof a) {
		        if (asMap) {
		            for (const key of Object.keys(a)) {
		                a[key] = new classs(a[key]);
		            }
		            return a;
		        }
		        return new classs(a);
		    }
		    return a;
		}
	}
	export class SaveProviderProfile {
	    id: string;
	    providerId: string;
	    name: string;
	    baseUrl: string;
	    model: string;
	    reasoningEffort: string;
	    apiMode: string;
	    contextWindowTokens: number;
	    executablePath: string;
	    apiKey: string;
	    clearApiKey: boolean;
	
	    static createFrom(source: any = {}) {
	        return new SaveProviderProfile(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.id = source["id"];
	        this.providerId = source["providerId"];
	        this.name = source["name"];
	        this.baseUrl = source["baseUrl"];
	        this.model = source["model"];
	        this.reasoningEffort = source["reasoningEffort"];
	        this.apiMode = source["apiMode"];
	        this.contextWindowTokens = source["contextWindowTokens"];
	        this.executablePath = source["executablePath"];
	        this.apiKey = source["apiKey"];
	        this.clearApiKey = source["clearApiKey"];
	    }
	}
	export class SaveSavedWorkflowRequest {
	    id: string;
	    name: string;
	    template: string;
	    projectPath: string;
	    githubRepository: string;
	    intervalMinutes: number;
	    enabled: boolean;
	
	    static createFrom(source: any = {}) {
	        return new SaveSavedWorkflowRequest(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.id = source["id"];
	        this.name = source["name"];
	        this.template = source["template"];
	        this.projectPath = source["projectPath"];
	        this.githubRepository = source["githubRepository"];
	        this.intervalMinutes = source["intervalMinutes"];
	        this.enabled = source["enabled"];
	    }
	}
	export class SaveSettingsRequest {
	    projectPath: string;
	    baseUrl: string;
	    model: string;
	    apiKey: string;
	    clearApiKey: boolean;
	    approvalMode: string;
	    allowlist: string[];
	    maxToolIterations: number;
	    systemPrompt: string;
	    responseVerbosity: string;
	    temperature: number;
	    maxTokens: number;
	    enableMultimodalMessages: boolean;
	    guiInferenceMode?: string;
	    guiLocalBaseUrl?: string;
	    guiLocalModel?: string;
	    guiAllowRemoteSemanticTree?: boolean;
	    guiAllowRemoteScreenshots?: boolean;
	    guiAllowRemoteFullScreen?: boolean;
	    visionRoutingEnabled?: boolean;
	    visionProviderProfileId?: string;
	    visionModel?: string;
	    visionCustomBaseUrl?: string;
	    visionApiKey?: string;
	    clearVisionApiKey?: boolean;
	    imageGenerationProviderProfileId?: string;
	    imageGenerationModel?: string;
	    imageGenerationCustomBaseUrl?: string;
	    imageGenerationApiKey?: string;
	    clearImageGenerationApiKey?: boolean;
	    imageGenerationSize?: string;
	    imageGenerationQuality?: string;
	    imageGenerationFormat?: string;
	    imageGenerationCompression?: number;
	    imageGenerationPartialImages?: number;
	    imageUpscaleBaseUrl?: string;
	    imageUpscaleModel?: string;
	    imageUpscaleApiKey?: string;
	    clearImageUpscaleApiKey?: boolean;
	    imageUpscaleScale?: number;
	    plannerProfileEnabled: boolean;
	    plannerModel: string;
	    plannerReasoningEffort: string;
	    subagentDefaultProfileEnabled: boolean;
	    subagentDefaultModel: string;
	    subagentDefaultReasoningEffort: string;
	    activeProviderProfileId: string;
	    providerProfiles: SaveProviderProfile[];
	    enabledBuiltinTools: string[];
	    enabledFileOperations: string[];
	    saveProjectToolPreferences: boolean;
	    useGlobalProjectTools: boolean;
	    projectTools: ToolPreferences;
	
	    static createFrom(source: any = {}) {
	        return new SaveSettingsRequest(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.projectPath = source["projectPath"];
	        this.baseUrl = source["baseUrl"];
	        this.model = source["model"];
	        this.apiKey = source["apiKey"];
	        this.clearApiKey = source["clearApiKey"];
	        this.approvalMode = source["approvalMode"];
	        this.allowlist = source["allowlist"];
	        this.maxToolIterations = source["maxToolIterations"];
	        this.systemPrompt = source["systemPrompt"];
	        this.responseVerbosity = source["responseVerbosity"];
	        this.temperature = source["temperature"];
	        this.maxTokens = source["maxTokens"];
	        this.enableMultimodalMessages = source["enableMultimodalMessages"];
	        this.guiInferenceMode = source["guiInferenceMode"];
	        this.guiLocalBaseUrl = source["guiLocalBaseUrl"];
	        this.guiLocalModel = source["guiLocalModel"];
	        this.guiAllowRemoteSemanticTree = source["guiAllowRemoteSemanticTree"];
	        this.guiAllowRemoteScreenshots = source["guiAllowRemoteScreenshots"];
	        this.guiAllowRemoteFullScreen = source["guiAllowRemoteFullScreen"];
	        this.visionRoutingEnabled = source["visionRoutingEnabled"];
	        this.visionProviderProfileId = source["visionProviderProfileId"];
	        this.visionModel = source["visionModel"];
	        this.visionCustomBaseUrl = source["visionCustomBaseUrl"];
	        this.visionApiKey = source["visionApiKey"];
	        this.clearVisionApiKey = source["clearVisionApiKey"];
	        this.imageGenerationProviderProfileId = source["imageGenerationProviderProfileId"];
	        this.imageGenerationModel = source["imageGenerationModel"];
	        this.imageGenerationCustomBaseUrl = source["imageGenerationCustomBaseUrl"];
	        this.imageGenerationApiKey = source["imageGenerationApiKey"];
	        this.clearImageGenerationApiKey = source["clearImageGenerationApiKey"];
	        this.imageGenerationSize = source["imageGenerationSize"];
	        this.imageGenerationQuality = source["imageGenerationQuality"];
	        this.imageGenerationFormat = source["imageGenerationFormat"];
	        this.imageGenerationCompression = source["imageGenerationCompression"];
	        this.imageGenerationPartialImages = source["imageGenerationPartialImages"];
	        this.imageUpscaleBaseUrl = source["imageUpscaleBaseUrl"];
	        this.imageUpscaleModel = source["imageUpscaleModel"];
	        this.imageUpscaleApiKey = source["imageUpscaleApiKey"];
	        this.clearImageUpscaleApiKey = source["clearImageUpscaleApiKey"];
	        this.imageUpscaleScale = source["imageUpscaleScale"];
	        this.plannerProfileEnabled = source["plannerProfileEnabled"];
	        this.plannerModel = source["plannerModel"];
	        this.plannerReasoningEffort = source["plannerReasoningEffort"];
	        this.subagentDefaultProfileEnabled = source["subagentDefaultProfileEnabled"];
	        this.subagentDefaultModel = source["subagentDefaultModel"];
	        this.subagentDefaultReasoningEffort = source["subagentDefaultReasoningEffort"];
	        this.activeProviderProfileId = source["activeProviderProfileId"];
	        this.providerProfiles = this.convertValues(source["providerProfiles"], SaveProviderProfile);
	        this.enabledBuiltinTools = source["enabledBuiltinTools"];
	        this.enabledFileOperations = source["enabledFileOperations"];
	        this.saveProjectToolPreferences = source["saveProjectToolPreferences"];
	        this.useGlobalProjectTools = source["useGlobalProjectTools"];
	        this.projectTools = this.convertValues(source["projectTools"], ToolPreferences);
	    }
	
		convertValues(a: any, classs: any, asMap: boolean = false): any {
		    if (!a) {
		        return a;
		    }
		    if (a.slice && a.map) {
		        return (a as any[]).map(elem => this.convertValues(elem, classs));
		    } else if ("object" === typeof a) {
		        if (asMap) {
		            for (const key of Object.keys(a)) {
		                a[key] = new classs(a[key]);
		            }
		            return a;
		        }
		        return new classs(a);
		    }
		    return a;
		}
	}
	
	
	
	
	export class SelectedChatImage {
	    attachment: MessageImageAttachment;
	    previewDataUrl: string;
	
	    static createFrom(source: any = {}) {
	        return new SelectedChatImage(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.attachment = this.convertValues(source["attachment"], MessageImageAttachment);
	        this.previewDataUrl = source["previewDataUrl"];
	    }
	
		convertValues(a: any, classs: any, asMap: boolean = false): any {
		    if (!a) {
		        return a;
		    }
		    if (a.slice && a.map) {
		        return (a as any[]).map(elem => this.convertValues(elem, classs));
		    } else if ("object" === typeof a) {
		        if (asMap) {
		            for (const key of Object.keys(a)) {
		                a[key] = new classs(a[key]);
		            }
		            return a;
		        }
		        return new classs(a);
		    }
		    return a;
		}
	}
	export class SendMessageRequest {
	    sessionId: string;
	    content: string;
	    goal?: string;
	    context?: ComposerContextItem[];
	    images?: MessageImageAttachment[];
	    mode?: string;
	
	    static createFrom(source: any = {}) {
	        return new SendMessageRequest(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.sessionId = source["sessionId"];
	        this.content = source["content"];
	        this.goal = source["goal"];
	        this.context = this.convertValues(source["context"], ComposerContextItem);
	        this.images = this.convertValues(source["images"], MessageImageAttachment);
	        this.mode = source["mode"];
	    }
	
		convertValues(a: any, classs: any, asMap: boolean = false): any {
		    if (!a) {
		        return a;
		    }
		    if (a.slice && a.map) {
		        return (a as any[]).map(elem => this.convertValues(elem, classs));
		    } else if ("object" === typeof a) {
		        if (asMap) {
		            for (const key of Object.keys(a)) {
		                a[key] = new classs(a[key]);
		            }
		            return a;
		        }
		        return new classs(a);
		    }
		    return a;
		}
	}
	
	
	export class SessionPointRequest {
	    sessionId: string;
	    messageId?: string;
	    confirmed?: boolean;
	
	    static createFrom(source: any = {}) {
	        return new SessionPointRequest(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.sessionId = source["sessionId"];
	        this.messageId = source["messageId"];
	        this.confirmed = source["confirmed"];
	    }
	}
	export class SessionProjectBindingRequest {
	    sessionId: string;
	    projectPath: string;
	
	    static createFrom(source: any = {}) {
	        return new SessionProjectBindingRequest(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.sessionId = source["sessionId"];
	        this.projectPath = source["projectPath"];
	    }
	}
	export class SessionSelection {
	    session?: ChatSession;
	    config: PublicDesktopConfig;
	    projectAvailable: boolean;
	    projectError?: string;
	
	    static createFrom(source: any = {}) {
	        return new SessionSelection(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.session = this.convertValues(source["session"], ChatSession);
	        this.config = this.convertValues(source["config"], PublicDesktopConfig);
	        this.projectAvailable = source["projectAvailable"];
	        this.projectError = source["projectError"];
	    }
	
		convertValues(a: any, classs: any, asMap: boolean = false): any {
		    if (!a) {
		        return a;
		    }
		    if (a.slice && a.map) {
		        return (a as any[]).map(elem => this.convertValues(elem, classs));
		    } else if ("object" === typeof a) {
		        if (asMap) {
		            for (const key of Object.keys(a)) {
		                a[key] = new classs(a[key]);
		            }
		            return a;
		        }
		        return new classs(a);
		    }
		    return a;
		}
	}
	export class SessionStats {
	    projectPath?: string;
	    messageCount: number;
	    userMessages: number;
	    assistantMessages: number;
	    toolMessages: number;
	    characterCount: number;
	    estimatedTokens: number;
	    modelRequests: number;
	    reportedUsageRequests: number;
	    providerInputTokens: number;
	    providerOutputTokens: number;
	    providerTotalTokens: number;
	    cachedInputTokens: number;
	    reasoningOutputTokens: number;
	    providerUsageAvailable: boolean;
	    providerUsageComplete: boolean;
	    lastProviderId?: string;
	    lastModel?: string;
	    compressionAvailable: boolean;
	    compressionActive: boolean;
	    compressionVersion?: number;
	    compressionMessages?: number;
	    compressionMethod?: string;
	    compressionCreatedAt?: number;
	    createdAt: number;
	    updatedAt: number;
	
	    static createFrom(source: any = {}) {
	        return new SessionStats(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.projectPath = source["projectPath"];
	        this.messageCount = source["messageCount"];
	        this.userMessages = source["userMessages"];
	        this.assistantMessages = source["assistantMessages"];
	        this.toolMessages = source["toolMessages"];
	        this.characterCount = source["characterCount"];
	        this.estimatedTokens = source["estimatedTokens"];
	        this.modelRequests = source["modelRequests"];
	        this.reportedUsageRequests = source["reportedUsageRequests"];
	        this.providerInputTokens = source["providerInputTokens"];
	        this.providerOutputTokens = source["providerOutputTokens"];
	        this.providerTotalTokens = source["providerTotalTokens"];
	        this.cachedInputTokens = source["cachedInputTokens"];
	        this.reasoningOutputTokens = source["reasoningOutputTokens"];
	        this.providerUsageAvailable = source["providerUsageAvailable"];
	        this.providerUsageComplete = source["providerUsageComplete"];
	        this.lastProviderId = source["lastProviderId"];
	        this.lastModel = source["lastModel"];
	        this.compressionAvailable = source["compressionAvailable"];
	        this.compressionActive = source["compressionActive"];
	        this.compressionVersion = source["compressionVersion"];
	        this.compressionMessages = source["compressionMessages"];
	        this.compressionMethod = source["compressionMethod"];
	        this.compressionCreatedAt = source["compressionCreatedAt"];
	        this.createdAt = source["createdAt"];
	        this.updatedAt = source["updatedAt"];
	    }
	}
	
	
	export class SetProviderModelRequest {
	    providerProfileId: string;
	    model: string;
	
	    static createFrom(source: any = {}) {
	        return new SetProviderModelRequest(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.providerProfileId = source["providerProfileId"];
	        this.model = source["model"];
	    }
	}
	export class SetProviderReasoningEffortRequest {
	    providerProfileId: string;
	    reasoningEffort: string;
	
	    static createFrom(source: any = {}) {
	        return new SetProviderReasoningEffortRequest(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.providerProfileId = source["providerProfileId"];
	        this.reasoningEffort = source["reasoningEffort"];
	    }
	}
	export class SetSessionCompressionRequest {
	    sessionId: string;
	    active: boolean;
	
	    static createFrom(source: any = {}) {
	        return new SetSessionCompressionRequest(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.sessionId = source["sessionId"];
	        this.active = source["active"];
	    }
	}
	export class SetSessionGoalStatusRequest {
	    sessionId: string;
	    status: string;
	
	    static createFrom(source: any = {}) {
	        return new SetSessionGoalStatusRequest(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.sessionId = source["sessionId"];
	        this.status = source["status"];
	    }
	}
	export class StartRemoteNodeRequest {
	    config: desktopbridge.RemoteNodeConfig;
	    pairCode: string;
	
	    static createFrom(source: any = {}) {
	        return new StartRemoteNodeRequest(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.config = this.convertValues(source["config"], desktopbridge.RemoteNodeConfig);
	        this.pairCode = source["pairCode"];
	    }
	
		convertValues(a: any, classs: any, asMap: boolean = false): any {
		    if (!a) {
		        return a;
		    }
		    if (a.slice && a.map) {
		        return (a as any[]).map(elem => this.convertValues(elem, classs));
		    } else if ("object" === typeof a) {
		        if (asMap) {
		            for (const key of Object.keys(a)) {
		                a[key] = new classs(a[key]);
		            }
		            return a;
		        }
		        return new classs(a);
		    }
		    return a;
		}
	}
	
	export class SyncCredentialsOperationResult {
	    direction: string;
	    importedSessions: number;
	    conflictSessions: number;
	    skippedSessions: number;
	    importedProviders: number;
	    importedApiKeys: number;
	    importedCodexLogin: boolean;
	    importedCodexAccounts: number;
	    importedGitHubToken: boolean;
	    importedGitHubAccounts: number;
	    accountEmail?: string;
	    importedSettings: boolean;
	    importedRules: number;
	    importedMemories: number;
	    importedSkills: number;
	    importedMcpServers: number;
	    importedWorkflows: number;
	    disabledMcpServers: number;
	    skippedWorkflows: number;
	    config: PublicDesktopConfig;
	    codex: CodexRuntimeStatus;
	
	    static createFrom(source: any = {}) {
	        return new SyncCredentialsOperationResult(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.direction = source["direction"];
	        this.importedSessions = source["importedSessions"];
	        this.conflictSessions = source["conflictSessions"];
	        this.skippedSessions = source["skippedSessions"];
	        this.importedProviders = source["importedProviders"];
	        this.importedApiKeys = source["importedApiKeys"];
	        this.importedCodexLogin = source["importedCodexLogin"];
	        this.importedCodexAccounts = source["importedCodexAccounts"];
	        this.importedGitHubToken = source["importedGitHubToken"];
	        this.importedGitHubAccounts = source["importedGitHubAccounts"];
	        this.accountEmail = source["accountEmail"];
	        this.importedSettings = source["importedSettings"];
	        this.importedRules = source["importedRules"];
	        this.importedMemories = source["importedMemories"];
	        this.importedSkills = source["importedSkills"];
	        this.importedMcpServers = source["importedMcpServers"];
	        this.importedWorkflows = source["importedWorkflows"];
	        this.disabledMcpServers = source["disabledMcpServers"];
	        this.skippedWorkflows = source["skippedWorkflows"];
	        this.config = this.convertValues(source["config"], PublicDesktopConfig);
	        this.codex = this.convertValues(source["codex"], CodexRuntimeStatus);
	    }
	
		convertValues(a: any, classs: any, asMap: boolean = false): any {
		    if (!a) {
		        return a;
		    }
		    if (a.slice && a.map) {
		        return (a as any[]).map(elem => this.convertValues(elem, classs));
		    } else if ("object" === typeof a) {
		        if (asMap) {
		            for (const key of Object.keys(a)) {
		                a[key] = new classs(a[key]);
		            }
		            return a;
		        }
		        return new classs(a);
		    }
		    return a;
		}
	}
	export class SyncCredentialsRequest {
	    includeSessions: boolean;
	    includeProviderCredentials: boolean;
	    includeCodexLogin: boolean;
	    includeGitHubCredentials: boolean;
	    includeAgentSettings: boolean;
	    includeKnowledge: boolean;
	    includeMcp: boolean;
	    includeMcpCredentials: boolean;
	    includeSavedWorkflows: boolean;
	
	    static createFrom(source: any = {}) {
	        return new SyncCredentialsRequest(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.includeSessions = source["includeSessions"];
	        this.includeProviderCredentials = source["includeProviderCredentials"];
	        this.includeCodexLogin = source["includeCodexLogin"];
	        this.includeGitHubCredentials = source["includeGitHubCredentials"];
	        this.includeAgentSettings = source["includeAgentSettings"];
	        this.includeKnowledge = source["includeKnowledge"];
	        this.includeMcp = source["includeMcp"];
	        this.includeMcpCredentials = source["includeMcpCredentials"];
	        this.includeSavedWorkflows = source["includeSavedWorkflows"];
	    }
	}
	
	
	export class UpscaleImageRequest {
	    sessionId: string;
	    messageId: string;
	
	    static createFrom(source: any = {}) {
	        return new UpscaleImageRequest(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.sessionId = source["sessionId"];
	        this.messageId = source["messageId"];
	    }
	}
	export class VoiceRuntimeStatus {
	    runtimeInstalled: boolean;
	    ttsModelInstalled: boolean;
	    asrModelInstalled: boolean;
	    busy: boolean;
	    message: string;
	    recording: boolean;
	    listening: boolean;
	    partialText: string;
	    speaking: boolean;
	    lastError: string;
	
	    static createFrom(source: any = {}) {
	        return new VoiceRuntimeStatus(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.runtimeInstalled = source["runtimeInstalled"];
	        this.ttsModelInstalled = source["ttsModelInstalled"];
	        this.asrModelInstalled = source["asrModelInstalled"];
	        this.busy = source["busy"];
	        this.message = source["message"];
	        this.recording = source["recording"];
	        this.listening = source["listening"];
	        this.partialText = source["partialText"];
	        this.speaking = source["speaking"];
	        this.lastError = source["lastError"];
	    }
	}
	export class WorkbenchFileAsset {
	    path: string;
	    mimeType: string;
	    base64: string;
	    sha256: string;
	    size: number;
	    width?: number;
	    height?: number;
	
	    static createFrom(source: any = {}) {
	        return new WorkbenchFileAsset(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.path = source["path"];
	        this.mimeType = source["mimeType"];
	        this.base64 = source["base64"];
	        this.sha256 = source["sha256"];
	        this.size = source["size"];
	        this.width = source["width"];
	        this.height = source["height"];
	    }
	}
	export class WorkbenchFileDocument {
	    path: string;
	    content: string;
	    sha256: string;
	    size: number;
	
	    static createFrom(source: any = {}) {
	        return new WorkbenchFileDocument(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.path = source["path"];
	        this.content = source["content"];
	        this.sha256 = source["sha256"];
	        this.size = source["size"];
	    }
	}
	export class WorkbenchFileEntry {
	    path: string;
	    name: string;
	    directory: boolean;
	    size?: number;
	    preview?: string;
	
	    static createFrom(source: any = {}) {
	        return new WorkbenchFileEntry(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.path = source["path"];
	        this.name = source["name"];
	        this.directory = source["directory"];
	        this.size = source["size"];
	        this.preview = source["preview"];
	    }
	}
	export class WorkbenchGitSnapshot {
	    repository: boolean;
	    branch?: string;
	    status: string;
	    diff: string;
	    message?: string;
	
	    static createFrom(source: any = {}) {
	        return new WorkbenchGitSnapshot(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.repository = source["repository"];
	        this.branch = source["branch"];
	        this.status = source["status"];
	        this.diff = source["diff"];
	        this.message = source["message"];
	    }
	}
	export class WorkbenchSaveFileRequest {
	    path: string;
	    content: string;
	    expectedSha256: string;
	
	    static createFrom(source: any = {}) {
	        return new WorkbenchSaveFileRequest(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.path = source["path"];
	        this.content = source["content"];
	        this.expectedSha256 = source["expectedSha256"];
	    }
	}
	export class WorkbenchTerminalResizeRequest {
	    sessionId: string;
	    columns: number;
	    rows: number;
	
	    static createFrom(source: any = {}) {
	        return new WorkbenchTerminalResizeRequest(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.sessionId = source["sessionId"];
	        this.columns = source["columns"];
	        this.rows = source["rows"];
	    }
	}
	export class WorkbenchTerminalSessionInfo {
	    sessionId: string;
	    clientId: string;
	    terminal: string;
	    label: string;
	    version?: string;
	    directory: string;
	
	    static createFrom(source: any = {}) {
	        return new WorkbenchTerminalSessionInfo(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.sessionId = source["sessionId"];
	        this.clientId = source["clientId"];
	        this.terminal = source["terminal"];
	        this.label = source["label"];
	        this.version = source["version"];
	        this.directory = source["directory"];
	    }
	}
	export class WorkbenchTerminalStartRequest {
	    clientId: string;
	    terminalId: string;
	    directory: string;
	    columns: number;
	    rows: number;
	
	    static createFrom(source: any = {}) {
	        return new WorkbenchTerminalStartRequest(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.clientId = source["clientId"];
	        this.terminalId = source["terminalId"];
	        this.directory = source["directory"];
	        this.columns = source["columns"];
	        this.rows = source["rows"];
	    }
	}
	export class WorkbenchTerminalWriteRequest {
	    sessionId: string;
	    data: string;
	
	    static createFrom(source: any = {}) {
	        return new WorkbenchTerminalWriteRequest(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.sessionId = source["sessionId"];
	        this.data = source["data"];
	    }
	}
	
	
	export class WorkspaceDiff {
	    path: string;
	    diff: string;
	    git: boolean;
	    available: boolean;
	    truncated: boolean;
	    message?: string;
	
	    static createFrom(source: any = {}) {
	        return new WorkspaceDiff(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.path = source["path"];
	        this.diff = source["diff"];
	        this.git = source["git"];
	        this.available = source["available"];
	        this.truncated = source["truncated"];
	        this.message = source["message"];
	    }
	}
	
	
	
	

}

