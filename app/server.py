from flask import Flask, request, jsonify, abort
import firebase_admin
from firebase_admin import credentials
from firebase_admin import db

cred = credentials.Certificate("ubicomp-b6a69-firebase-adminsdk-sogla-035a5bc421.json")
my_app = firebase_admin.initialize_app(cred,
                                       {
    'databaseURL': 'https://ubicomp-b6a69.firebaseio.com'
})
print(my_app.project_id)

# As an admin, the app has access to read and write all data, regradless of Security Rules
POINTS = db.reference('points')

app = Flask(__name__)

# root
@app.route("/")
def index():
    """
    this is a root dir of my server
    :return: str
    """
    return {'data': 'UbiComp Flask Service'}

# GET
@app.route('/points/<id>')
def get_point(id):
    """
    Get point with matching id
    :param id: ID of point
    :return: str
    """
    return jsonify(_ensure_point(id))

# POST
@app.route('/points', methods=['POST'])
def create_point_entry():
    """
    Adds a point to firebase.
    :return: json
    """
    req = request.get_json(force=True)
    entry = POINTS.push(req)
    print('content: {}'.format(req))
    return jsonify({'id': entry.key}), 201


def _ensure_point(id):
    point = POINTS.child(id).get()
    if not point:
        abort(404)
    return point


# running web app in local machine
if __name__ == '__main__':
    app.run(ssl_context='adhoc', port=8888)
