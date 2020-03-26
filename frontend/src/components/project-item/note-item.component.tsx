import React, {useState} from 'react';
import {
    DeleteTwoTone,
    EditTwoTone,
    FormOutlined,
    InfoCircleOutlined,
    MessageOutlined,
    MoreOutlined,
} from '@ant-design/icons';
import {deleteNote} from '../../features/notes/actions';
import {Note} from '../../features/notes/interface';
import {connect} from 'react-redux';
import {Link} from 'react-router-dom';
import EditNote from '../modals/edit-note.component';

import {Popconfirm, Popover} from 'antd';
import EditProjectItem from "../modals/move-project-item.component";

type NoteProps = {
    note: Note;
    deleteNote: (noteId: number) => void;
};

const Content: React.FC<NoteProps> = props => {
    const {note, deleteNote} = props;
    const [visible, setVisible] = useState(false);
    return (
        <div style={{display: 'flex', flexDirection: 'column'}}>
            <Popconfirm
                title="Deleting Note also deletes its child notes. Are you sure?"
                okText="Yes"
                cancelText="No"
                onConfirm={() => deleteNote(note.id)}
                className="group-setting"
                placement="bottom"
            >
                <div>
                    Delete
                    <DeleteTwoTone twoToneColor="#f5222d"/>
                </div>
            </Popconfirm>
            <div>
                <span>Edit</span>
                <EditTwoTone onClick={() => setVisible(!visible)}/>
                <EditNote visible={visible} note={note} setVisible={setVisible}/>
            </div>
            <div>
                <EditProjectItem type='NOTE' projectItemId={note.id}/>
            </div>
        </div>
    );
};

const alignConfig = {
    offset: [10, -5]
};

const NoteItem: React.FC<NoteProps> = props => {
    const {note, deleteNote} = props;

    return (
        <div
            style={{
                width: '100%',
                height: '2rem',
                position: 'relative',
                lineHeight: '2rem'
            }}
        >

            <Link to={`/note/${note.id}`}>
                <FormOutlined/>
                <span style={{padding: '0 5px', height: '100%'}}>{note.name}</span>
            </Link>
            <div
                style={{
                    width: '300px',
                    height: '100%',
                    position: 'absolute',
                    top: 0,
                    right: 0,
                    display: 'flex',
                    justifyContent: 'flex-end',
                    alignItems: 'center'
                }}
            >
                <InfoCircleOutlined style={{marginRight: '1em'}}/>
                <MessageOutlined style={{marginRight: '1em'}}/>
                <Popover
                    align={alignConfig}
                    placement="bottomRight"
                    style={{top: -10}}
                    content={
                        <Content note={note} deleteNote={deleteNote}/>
                    }
                    trigger="click"
                >
                    <MoreOutlined
                        style={{transform: 'rotate(90deg)', fontSize: '20px'}}
                    />
                </Popover>
            </div>
        </div>
    );
};

export default connect(null, {deleteNote})(NoteItem);
